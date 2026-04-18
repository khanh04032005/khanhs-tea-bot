package com.khanh.teashop.khanhs_tea_bot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    @Nationalized
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Nationalized
    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "price_m", nullable = false)
    private int priceM;

    @Column(name = "price_l", nullable = false)
    private int priceL;

    @Column(name = "available", nullable = false)
    private boolean available;
}