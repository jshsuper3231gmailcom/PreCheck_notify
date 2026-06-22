package com.sks.precheck.notify.vo;

import com.sks.precheck.notify.common.constants.NotifyConstants;

public class NotifyScheduleVo {

    private String scheduleType;
    private String dayOfWeek;
    private String startTime;
    private Integer intervalMinutes;
    private String endTime;

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(String dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public Integer getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(Integer intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    /**
     * 통보주기기술 원문 형태로 재구성 — 스케쥴러의 중복실행 방지 키 생성에 사용 (통보_스케쥴러정의서.md "중복 실행 방지" 참고)
     */
    public String toScheduleExpression() {
        if (NotifyConstants.SCHEDULE_TYPE_BATCH.equals(scheduleType)) {
            return scheduleType + "|" + dayOfWeek + "|" + startTime;
        }
        return scheduleType + "|" + dayOfWeek + "|" + startTime + "|" + intervalMinutes + "|" + endTime;
    }
}
