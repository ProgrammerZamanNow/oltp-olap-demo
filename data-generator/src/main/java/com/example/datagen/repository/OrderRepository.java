package com.example.datagen.repository;

import com.example.datagen.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = "SELECT * FROM orders WHERE status IN ('PLACED','PAID') ORDER BY random() LIMIT 1", nativeQuery = true)
    Optional<Order> pickActive();
}
