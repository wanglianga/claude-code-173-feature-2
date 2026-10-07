package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 会员账户：扣款与退款回补的余额载体。
 * 余额变更只能通过 {@link AccountTxn} 流水发生，保证可对账。
 */
@Entity
@Table(name = "member_account",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberAccount extends BaseEntity {

    /** 用户标识（与 door_session.user_code 一致） */
    private String userCode;

    private String userName;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;
}
