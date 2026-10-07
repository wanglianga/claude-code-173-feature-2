package com.cabinet.service;

import com.cabinet.domain.Channel;
import com.cabinet.domain.ChannelHealthRecord;
import com.cabinet.domain.enums.AlertType;
import com.cabinet.domain.enums.ChannelHealth;
import com.cabinet.repo.ChannelHealthRecordRepository;
import com.cabinet.repo.ChannelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 货道健康度：区分“单次误识别”与“设备故障”。
 * 每次复核确认误判退款后登记一次；在滚动窗口（默认 7 天）内：
 * - 首次误判：SUSPECT 观察（单次误识别，不停用）；
 * - 累计达到阈值（默认 3 次）：判定为设备故障，自动暂停货道售卖并告警安排维护。
 */
@Service
@RequiredArgsConstructor
public class ChannelHealthService {

    private final ChannelHealthRecordRepository healthRepository;
    private final ChannelRepository channelRepository;
    private final AlertService alertService;

    @Value("${cabinet.review.miscount-window-hours:168}")
    private int windowHours;

    @Value("${cabinet.review.miscount-suspend-threshold:3}")
    private int suspendThreshold;

    /**
     * 登记一次复核确认的货道误识别。
     * @return 登记后的健康档案；达到阈值时货道已被暂停
     */
    @Transactional
    public ChannelHealthRecord registerMiscount(Channel channel, String reviewNo, String note) {
        ChannelHealthRecord h = healthRepository.lockByChannelId(channel.getId())
                .orElseGet(() -> ChannelHealthRecord.builder()
                        .channel(channel).health(ChannelHealth.HEALTHY)
                        .windowStart(LocalDateTime.now())
                        .miscountInWindow(0).miscountTotal(0).build());

        LocalDateTime now = LocalDateTime.now();
        if (h.getWindowStart() == null
                || h.getWindowStart().plusHours(windowHours).isBefore(now)) {
            // 开启新的滚动窗口
            h.setWindowStart(now);
            h.setMiscountInWindow(0);
        }
        h.setMiscountInWindow(h.getMiscountInWindow() + 1);
        h.setMiscountTotal(h.getMiscountTotal() + 1);
        h.setLastReviewNo(reviewNo);
        h.setLastMiscountAt(now);

        if (h.getMiscountInWindow() == 1 && h.getHealth() == ChannelHealth.HEALTHY) {
            h.setHealth(ChannelHealth.SUSPECT);
            alertService.raise(channel.getCabinet(), AlertType.CHANNEL_SUSPECT, "INFO",
                    "货道 " + channel.getCode() + " 出现误识别复核 " + reviewNo
                            + "（窗口内第 1 次，单次误识别观察，暂不停用）",
                    "CHANNEL", channel.getId());
        }

        if (h.getMiscountInWindow() >= suspendThreshold
                && h.getHealth() != ChannelHealth.SUSPENDED) {
            h.setHealth(ChannelHealth.SUSPENDED);
            h.setSuspendedAt(now);
            h.setSuspendReason("窗口 " + windowHours + "h 内误判 " + h.getMiscountInWindow()
                    + " 次达到阈值 " + suspendThreshold + "，疑似设备故障：" + note);
            channel.setSuspended(true);
            channelRepository.save(channel);
            alertService.raise(channel.getCabinet(), AlertType.CHANNEL_SUSPENDED, "CRITICAL",
                    "货道 " + channel.getCode() + " 反复误识别（窗口内 "
                            + h.getMiscountInWindow() + " 次），已暂停售卖，请安排维护：" + reviewNo,
                    "CHANNEL", channel.getId());
        }
        return healthRepository.save(h);
    }

    @Transactional(readOnly = true)
    public ChannelHealthRecord get(Channel channel) {
        return healthRepository.findByChannelId(channel.getId()).orElse(null);
    }
}
