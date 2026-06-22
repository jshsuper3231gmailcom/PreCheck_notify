package com.sks.precheck.notify.mapper;

import com.sks.precheck.notify.domain.AnalyzeLevelCount;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * TB_ANALYZE_RESULT(분석서버 소유 테이블)에 대한 notify 자체 매퍼.
 * analyze가 collect의 TB_COLLECT_LOG를 자체 CollectLogMapper로 직접 조회하는 기존 패턴과 동일하게,
 * notify도 모듈 간 코드 공유 없이 자체 매퍼를 정의한다 (통보_DB정의서.md 7-2/7-4 참고).
 */
@Mapper
public interface AnalyzeResultMapper {

    List<String> selectDistinctServerIds();

    List<AnalyzeLevelCount> countByLevelInWindow(
            @Param("serverId") String serverId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    int updateNotifyYn(
            @Param("serverId") String serverId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("notifyAt") LocalDateTime notifyAt
    );
}
