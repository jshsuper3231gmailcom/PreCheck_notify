package com.sks.precheck.notify.mapper;

import com.sks.precheck.notify.domain.NotifyHistory;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotifyHistoryMapper {

    // 이 서버의 마지막 SUCCESS/PARTIAL 행의 AGGREGATE_TO (워터마크). 없으면 null (통보_DB정의서.md 7-1)
    LocalDateTime findLastWatermark(@Param("serverId") String serverId);

    int insert(NotifyHistory notifyHistory);
}
