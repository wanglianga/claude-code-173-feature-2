package com.cabinet.domain;

import com.cabinet.domain.enums.DetectAction;
import com.cabinet.domain.enums.RecognitionSource;
import com.cabinet.domain.enums.SampleVerdict;
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

    /** 设备事件 ID（与 device_event_log 对应，用于补传溯源） */
    private String eventId;

    /** 是否补传事件（设备离线恢复后集中上报/会话已结束时迟到到达，仅存档不触发结算） */
    @Builder.Default
    private boolean backfill = false;

    /** 识别样本判定：复核退款/驳回时同步修正，供识别模型纠偏 */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SampleVerdict verdict = SampleVerdict.NONE;

    /** 样本判定说明（哪张投诉单、何种结论） */
    @Column(length = 500)
    private String verdictNote;
}
