package com.sks.precheck.notify.vo;

import java.time.LocalDateTime;

/** NotifyAggregationService가 서버 1건에 대해 집계한 결과 (경고+에러가 1건 이상이라 통보 대상인 서버만 생성됨). */
public class ServerAggregateResult {

    private String serverId;
    private LocalDateTime aggregateFrom;
    private LocalDateTime aggregateTo;
    private long normalCount;
    private long warningCount;
    private long errorCount;
    private String message;

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public LocalDateTime getAggregateFrom() {
        return aggregateFrom;
    }

    public void setAggregateFrom(LocalDateTime aggregateFrom) {
        this.aggregateFrom = aggregateFrom;
    }

    public LocalDateTime getAggregateTo() {
        return aggregateTo;
    }

    public void setAggregateTo(LocalDateTime aggregateTo) {
        this.aggregateTo = aggregateTo;
    }

    public long getNormalCount() {
        return normalCount;
    }

    public void setNormalCount(long normalCount) {
        this.normalCount = normalCount;
    }

    public long getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(long warningCount) {
        this.warningCount = warningCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(long errorCount) {
        this.errorCount = errorCount;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
