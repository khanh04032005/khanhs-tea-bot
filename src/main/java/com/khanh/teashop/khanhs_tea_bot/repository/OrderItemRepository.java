package com.khanh.teashop.khanhs_tea_bot.repository;

import com.khanh.teashop.khanhs_tea_bot.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}