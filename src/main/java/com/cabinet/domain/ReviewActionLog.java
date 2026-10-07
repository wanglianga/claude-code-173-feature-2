package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 复核操作留痕：谁、在什么时候、依据什么做出了什么判定。
 * 原始判断（SYSTEM_INITIAL）与每一次人工修正（退款/维持/驳回/补传数据到达）都追加保存，
 * 已结算订单、重复申诉、货道换机、离线集中上报等场景都能回放完整修正过程。
 */
@Entity
@Table(name = "review_action_log",
        indexes = @Index(columnList = "review_case_id,id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewActionLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_case_id")
    private ReviewCase reviewCase;

    /** SYSTEM_INITIAL / SUBMIT / REFUND / UPHOLD / REJECT / LATE_DATA / REOPEN / CABINET_REPLACED */
    private String action;

    /** 操作人（系统动作为 system） */
    private String actor;

    @Column(length = 2000)
    private String detail;

    private LocalDateTime actionAt;
}
