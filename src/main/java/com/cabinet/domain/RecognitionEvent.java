package com.cabinet.domain;

import com.cabinet.domain.enums.DetectAction;
import com.cabinet.domain.enums.RecognitionSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 识别事件：重量/视觉/RFID 对用户拿起、放回动作的识别结果。
 * 同一拿放动作可能有多源事件，结算时做融合与冲突检测。
 */
@Entity
@Table(name = "recognition_event",
        indexes = @Index(columnList = "session_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecognitionEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private DoorSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    /** 货道位置 */
    private String channelCode;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    /** 若能识别到具体批次则记录（RFID） */
    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    @Enumerated(EnumType.STRING)
    private RecognitionSource source;

    @Enumerated(EnumType.STRING)
    private DetectAction action;

    private Integer qty;

    private Double confidence;

    private LocalDateTime detectedAt;

    /** 单次称重值(g) */
    private Integer weightGram;

    /** 视频片段定位（URL + 起止秒），供客服回放 */
    private String videoClipUrl;
    private Integer clipStartSec;
    private Integer clipEndSec;

    /** 结算时融合归并标记：同一动作组 */
    private String fusionGroup;

    /** 识别原文（设备上报 JSON 快照） */
    @Column(length = 2000)
    private String rawPayload;

    /** 关门/结算后才到达（设备离线集中补传），原始事件保留但不参与自动结算 */
    @Builder.Default
    private boolean lateArrival = false;
}
