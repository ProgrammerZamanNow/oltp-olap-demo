package com.example.datagen.repository;

import com.example.datagen.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = "SELECT * FROM orders WHERE status IN ('PLACED','PAID') ORDER BY random() LIMIT 1", nativeQuery = true)
    Optional<Order> pickActive();

    @Query(value = "SELECT * FROM orders WHERE status IN ('PLACED','PAID','SHIPPED') ORDER BY random() LIMIT :n", nativeQuery = true)
    java.util.List<Order> pickActiveBatch(int n);

    @Query(value = """
        (SELECT * FROM orders WHERE status = 'PLACED'  ORDER BY random() LIMIT :n)
        UNION ALL
        (SELECT * FROM orders WHERE status = 'PAID'    ORDER BY random() LIMIT :n)
        UNION ALL
        (SELECT * FROM orders WHERE status = 'SHIPPED' ORDER BY random() LIMIT :n)
        """, nativeQuery = true)
    java.util.List<Order> pickActiveStratified(int n);

    @Query(value = "SELECT * FROM orders WHERE status = :status ORDER BY random() LIMIT :n", nativeQuery = true)
    java.util.List<Order> pickByStatus(String status, int n);

    @Query(value = "SELECT count(*) FROM orders WHERE status = :status", nativeQuery = true)
    long countByStatus(String status);
}
