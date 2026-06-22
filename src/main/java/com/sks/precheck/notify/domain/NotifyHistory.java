package com.sks.precheck.notify.domain;

import java.time.LocalDateTime;

public class NotifyHistory {

    private Long notifyHistoryId;
    private String serverId;

    private LocalDateTime aggregateFrom;
    private LocalDateTime aggregateTo;

    private Long normalCount;
    private Long warningCount;
    private Long errorCount;

    private Long recvTotalCount;
    private Long recvSuccessCount;
    private Long recvFailCount;

    private String notifyMessage;

    private String notifyStatus;
    private String failReason;

    private LocalDateTime notifyStartAt;
    private LocalDateTime notifyEndAt;
    private String notifyDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getNotifyHistoryId() {
        return notifyHistoryId;
    }

    public void setNotifyHistoryId(Long notifyHistoryId) {
        this.notifyHistoryId = notifyHistoryId;
    }

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

    public Long getNormalCount() {
        return normalCount;
    }

    public void setNormalCount(Long normalCount) {
        this.normalCount = normalCount;
    }

    public Long getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(Long warningCount) {
        this.warningCount = warningCount;
    }

    public Long getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Long errorCount) {
        this.errorCount = errorCount;
    }

    public Long getRecvTotalCount() {
        return recvTotalCount;
    }

    public void setRecvTotalCount(Long recvTotalCount) {
        this.recvTotalCount = recvTotalCount;
    }

    public Long getRecvSuccessCount() {
        return recvSuccessCount;
    }

    public void setRecvSuccessCount(Long recvSuccessCount) {
        this.recvSuccessCount = recvSuccessCount;
    }

    public Long getRecvFailCount() {
        return recvFailCount;
    }

    public void setRecvFailCount(Long recvFailCount) {
        this.recvFailCount = recvFailCount;
    }

    public String getNotifyMessage() {
        return notifyMessage;
    }

    public void setNotifyMessage(String notifyMessage) {
        this.notifyMessage = notifyMessage;
    }

    public String getNotifyStatus() {
        return notifyStatus;
    }

    public void setNotifyStatus(String notifyStatus) {
        this.notifyStatus = notifyStatus;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getNotifyStartAt() {
        return notifyStartAt;
    }

    public void setNotifyStartAt(LocalDateTime notifyStartAt) {
        this.notifyStartAt = notifyStartAt;
    }

    public LocalDateTime getNotifyEndAt() {
        return notifyEndAt;
    }

    public void setNotifyEndAt(LocalDateTime notifyEndAt) {
        this.notifyEndAt = notifyEndAt;
    }

    public String getNotifyDate() {
        return notifyDate;
    }

    public void setNotifyDate(String notifyDate) {
        this.notifyDate = notifyDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
