package com.sks.precheck.notify.service;

import com.sks.precheck.notify.common.constants.NotifyConstants;
import com.sks.precheck.notify.common.util.SequenceHelper;
import com.sks.precheck.notify.domain.NotifyHistory;
import com.sks.precheck.notify.mapper.AnalyzeResultMapper;
import com.sks.precheck.notify.mapper.NotifyHistoryMapper;
import com.sks.precheck.notify.parser.NotifyTargetParser;
import com.sks.precheck.notify.tr.SmsTrEncoder;
import com.sks.precheck.notify.tr.SmsTrSocketClient;
import com.sks.precheck.notify.tr.SmsTrSocketClientFactory;
import com.sks.precheck.notify.vo.NotifyScheduleVo;
import com.sks.precheck.notify.vo.ServerAggregateResult;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 통보 1회 실행의 오케스트레이션 — 집계 → TR 소켓 전송 → 서버별 즉시 이력 INSERT(crash-safe) → NOTIFY_YN 갱신
 *
 * connection은 이번 런 전체에서 1개만 열며, 통보 대상 서버 전체가 순서대로 그 connection을 공유한다.
 * 한 서버 처리가 끝나는 즉시 그 서버의 TB_NOTIFY_HISTORY를 INSERT하고, 처리 중 연결이 끊기면 그 서버는
 * PARTIAL/FAIL로 기록한 뒤 아직 처리하지 않은 나머지 서버는 전부 FAIL(0건 시도)로 기록하고 런을 종료한다
 * (재연결하지 않음 — 통보_TR연동정의서.md 5절, 통보_DB정의서.md 3절).
 */
@Service
public class NotifyService {

    private static final Logger log = LogManager.getLogger(NotifyService.class);

    private static final DateTimeFormatter NOTIFY_DATE_FORMATTER = DateTimeFormatter.ofPattern(NotifyConstants.NOTIFY_DATE_FORMAT);

    private final NotifyAggregationService aggregationService;
    private final NotifyHistoryMapper notifyHistoryMapper;
    private final AnalyzeResultMapper analyzeResultMapper;
    private final SequenceHelper sequenceHelper;
    private final NotifyTargetParser targetParser;
    private final SmsTrEncoder encoder;
    private final SmsTrSocketClientFactory socketClientFactory;

    private final String targetFilePath;
    private final String smsHost;
    private final int smsPort;

    public NotifyService(
            NotifyAggregationService aggregationService,
            NotifyHistoryMapper notifyHistoryMapper,
            AnalyzeResultMapper analyzeResultMapper,
            SequenceHelper sequenceHelper,
            SmsTrSocketClientFactory socketClientFactory,
            @Value("${precheck.notify.target-file-path}") String targetFilePath,
            @Value("${precheck.notify.sms.host}") String smsHost,
            @Value("${precheck.notify.sms.port}") int smsPort
    ) {
        this.aggregationService = aggregationService;
        this.notifyHistoryMapper = notifyHistoryMapper;
        this.analyzeResultMapper = analyzeResultMapper;
        this.sequenceHelper = sequenceHelper;
        this.socketClientFactory = socketClientFactory;
        this.targetParser = new NotifyTargetParser();
        this.encoder = new SmsTrEncoder();
        this.targetFilePath = targetFilePath;
        this.smsHost = smsHost;
        this.smsPort = smsPort;
    }

    public void runNotify(NotifyScheduleVo schedule, LocalDateTime aggregateTo) {
        List<ServerAggregateResult> targets = aggregationService.aggregate(schedule, aggregateTo);
        if (targets.isEmpty()) {
            return;
        }

        String notifyDate = aggregateTo.format(NOTIFY_DATE_FORMATTER);
        List<String> recipients = targetParser.parseTargetFile(targetFilePath);

        if (recipients.isEmpty()) {
            log.warn("수신대상 파일이 비어있어 통보를 건너뜀(SUCCESS로 기록, 워터마크는 전진) - targetFilePath: {}", targetFilePath);
            for (ServerAggregateResult target : targets) {
                LocalDateTime now = LocalDateTime.now();
                insertHistory(target, notifyDate, NotifyConstants.STATUS_SUCCESS, 0, 0, 0, null, now, now);
                updateNotifyYn(target);
            }
            return;
        }

        SmsTrSocketClient client;
        try {
            client = socketClientFactory.connect(smsHost, smsPort, NotifyConstants.SMS_CONNECT_TIMEOUT_MS, encoder, sequenceHelper);
        } catch (IOException e) {
            log.error("SMS 게이트웨이 connect 실패 - host: {}, port: {}", smsHost, smsPort, e);
            failAll(targets, notifyDate, "connect 실패: " + e.getMessage());
            return;
        }

        try (client) {
            try {
                client.sendBind();
            } catch (IOException e) {
                log.error("SMS TR bind 실패", e);
                failAll(targets, notifyDate, "bind 실패: " + e.getMessage());
                return;
            }

            boolean connectionDead = false;
            for (ServerAggregateResult target : targets) {
                if (connectionDead) {
                    insertHistory(target, notifyDate, NotifyConstants.STATUS_FAIL, 0, 0, 0,
                            "이전 서버 처리 중 연결이 끊겨 시도하지 못함", LocalDateTime.now(), LocalDateTime.now());
                    continue;
                }

                connectionDead = !processOneServer(client, target, recipients, notifyDate);
            }
        }
    }

    /** 서버 1건 처리. 정상적으로 처리가 끝났으면(SUCCESS) true, 연결이 끊겨 런을 종료해야 하면(PARTIAL/FAIL) false를 반환한다. */
    private boolean processOneServer(SmsTrSocketClient client, ServerAggregateResult target, List<String> recipients, String notifyDate) {
        LocalDateTime startAt = LocalDateTime.now();
        SmsTrSocketClient.SubmitResult result = client.submitAll(recipients, target.getMessage());
        LocalDateTime endAt = LocalDateTime.now();

        String status;
        String failReason = null;
        if (result.allSucceeded()) {
            status = NotifyConstants.STATUS_SUCCESS;
        } else if (result.anySucceeded()) {
            status = NotifyConstants.STATUS_PARTIAL;
            failReason = describeFailure(result);
        } else {
            status = NotifyConstants.STATUS_FAIL;
            failReason = describeFailure(result);
        }

        int recvFailCount = result.getTotalCount() - result.getSuccessCount();
        insertHistory(target, notifyDate, status, result.getTotalCount(), result.getSuccessCount(), recvFailCount, failReason, startAt, endAt);

        if (!NotifyConstants.STATUS_FAIL.equals(status)) {
            updateNotifyYn(target);
        }

        return result.allSucceeded();
    }

    private String describeFailure(SmsTrSocketClient.SubmitResult result) {
        String causeMessage = result.getFailure() != null ? result.getFailure().getMessage() : "알 수 없음";
        return "submit " + result.getSuccessCount() + "/" + result.getTotalCount() + "건 성공 후 연결 끊김: " + causeMessage;
    }

    private void failAll(List<ServerAggregateResult> targets, String notifyDate, String failReason) {
        for (ServerAggregateResult target : targets) {
            LocalDateTime now = LocalDateTime.now();
            insertHistory(target, notifyDate, NotifyConstants.STATUS_FAIL, 0, 0, 0, failReason, now, now);
        }
    }

    private void insertHistory(
            ServerAggregateResult target,
            String notifyDate,
            String status,
            int recvTotalCount,
            int recvSuccessCount,
            int recvFailCount,
            String failReason,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        NotifyHistory history = new NotifyHistory();
        history.setNotifyHistoryId(sequenceHelper.nextval("seq_notify_history"));
        history.setServerId(target.getServerId());
        history.setAggregateFrom(target.getAggregateFrom());
        history.setAggregateTo(target.getAggregateTo());
        history.setNormalCount(target.getNormalCount());
        history.setWarningCount(target.getWarningCount());
        history.setErrorCount(target.getErrorCount());
        history.setRecvTotalCount((long) recvTotalCount);
        history.setRecvSuccessCount((long) recvSuccessCount);
        history.setRecvFailCount((long) recvFailCount);
        history.setNotifyMessage(target.getMessage());
        history.setNotifyStatus(status);
        history.setFailReason(failReason);
        history.setNotifyStartAt(startAt);
        history.setNotifyEndAt(endAt);
        history.setNotifyDate(notifyDate);
        history.setCreatedAt(LocalDateTime.now());
        notifyHistoryMapper.insert(history);
    }

    private void updateNotifyYn(ServerAggregateResult target) {
        analyzeResultMapper.updateNotifyYn(
                target.getServerId(), target.getAggregateFrom(), target.getAggregateTo(), LocalDateTime.now());
    }
}
