package com.cabinet.repo;

import com.cabinet.domain.ChannelHealthRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChannelHealthRecordRepository extends JpaRepository<ChannelHealthRecord, Long> {
    Optional<ChannelHealthRecord> findByChannelId(Long channelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ChannelHealthRecord h where h.channel.id = :channelId")
    Optional<ChannelHealthRecord> lockByChannelId(@Param("channelId") Long channelId);
}
