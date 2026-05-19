package com.example.datagen.controller;

import com.example.datagen.entity.Product;
import com.example.datagen.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public Page<Product> list(
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return products.findAll(pageable);
    }
}
