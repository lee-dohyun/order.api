package com.dh.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.dh.order.domain.Channel;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
}
