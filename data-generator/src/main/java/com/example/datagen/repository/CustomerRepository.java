package com.example.datagen.repository;

import com.example.datagen.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    @Query(value = "SELECT * FROM customers ORDER BY random() LIMIT 1", nativeQuery = true)
    Optional<Customer> pickRandom();
}
