package com.sks.precheck.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.sks.precheck.notify.domain.AnalyzeLevelCount;
import com.sks.precheck.notify.mapper.AnalyzeResultMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * TB_ANALYZE_RESULT(analyze 소유 테이블)에 대한 notify 측 읽기/UPDATE SQL을 실제 로컬 PostgreSQL에 대해 검증.
 * analyze가 채워둔 실제 데이터를 변경하지 않도록, 존재하지 않는 서버ID로만 검증한다.
 */
@SpringBootTest
@Transactional
class AnalyzeResultMapperTest {

    @Autowired
    private AnalyzeResultMapper analyzeResultMapper;

    @Test
    void selectDistinctServerIds_executesWithoutError() {
        List<String> serverIds = analyzeResultMapper.selectDistinctServerIds();
        assertThat(serverIds).isNotNull();
    }

    @Test
    void countByLevelInWindow_noMatchingServer_returnsEmpty() {
        List<AnalyzeLevelCount> counts = analyzeResultMapper.countByLevelInWindow(
                "no-such-server-xyz", LocalDateTime.now().minusDays(1), LocalDateTime.now());
        assertThat(counts).isEmpty();
    }

    @Test
    void updateNotifyYn_noMatchingServer_updatesZeroRows() {
        int updated = analyzeResultMapper.updateNotifyYn(
                "no-such-server-xyz", LocalDateTime.now().minusDays(1), LocalDateTime.now(), LocalDateTime.now());
        assertThat(updated).isEqualTo(0);
    }
}
