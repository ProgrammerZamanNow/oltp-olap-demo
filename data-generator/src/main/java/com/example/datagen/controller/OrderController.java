package com.example.datagen.controller;

import com.example.datagen.entity.Order;
import com.example.datagen.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orders;

    public OrderController(OrderRepository orders) {
        this.orders = orders;
    }

    @GetMapping
    public Page<Order> list(
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orders.findAll(pageable);
    }
}
