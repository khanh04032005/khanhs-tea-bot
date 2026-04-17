package com.khanh.teashop.khanhs_tea_bot.repository;

import com.khanh.teashop.khanhs_tea_bot.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
}