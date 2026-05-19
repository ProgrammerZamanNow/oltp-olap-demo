package com.example.datagen.controller;

import com.example.datagen.entity.Customer;
import com.example.datagen.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerRepository customers;

    public CustomerController(CustomerRepository customers) {
        this.customers = customers;
    }

    @GetMapping
    public Page<Customer> list(
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return customers.findAll(pageable);
    }
}
