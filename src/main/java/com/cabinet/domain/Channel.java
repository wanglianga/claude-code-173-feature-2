package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

/** 货道：属于某柜机，计划售卖指定商品（点位品类配置） */
@Entity
@Table(name = "channel",
        uniqueConstraints = @UniqueConstraint(columnNames = {"cabinet_id", "code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Channel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cabinet_id")
    private Cabinet cabinet;

    /** 货道位置，如 A01 */
    private String code;

    /** 货道计划商品；补货放错货道即与该值不一致 */
    @ManyToOne(fetch = FetchType.LAZY)
    private Product plannedProduct;

    private Integer capacity;

    /** 货道因反复误识别被暂停售卖（设备故障待维护），暂停期间识别拿取不自动结算 */
    @Builder.Default
    private boolean suspended = false;

    /** 原柜机货道换机迁移到新柜机时记录原柜机编号，保留历史归属 */
    private String migratedFromCabinetNo;
    private String migratedFromChannelCode;
}
