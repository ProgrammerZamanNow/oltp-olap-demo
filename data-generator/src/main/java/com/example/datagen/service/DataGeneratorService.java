package com.example.datagen.service;

import com.example.datagen.entity.Customer;
import com.example.datagen.entity.Order;
import com.example.datagen.entity.OrderItem;
import com.example.datagen.entity.Product;
import com.example.datagen.repository.CustomerRepository;
import com.example.datagen.repository.OrderItemRepository;
import com.example.datagen.repository.OrderRepository;
import com.example.datagen.repository.ProductRepository;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DataGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DataGeneratorService.class);
    private static final List<String> NEXT_STATUSES = List.of("PAID", "SHIPPED", "DELIVERED", "CANCELLED");

    // Proportional drain rate per tick — bukan fixed batch, supaya queue stabil di
    // equilibrium (drain × queue = inflow). Equilibrium math:
    //   PLACED:   inflow 5/tick   drain 8%  → eq ≈ 5 / 0.08   = 62
    //   PAID:     inflow ≈ 4.5    drain 20% → eq ≈ 4.5 / 0.2  = 22
    //   SHIPPED:  inflow ≈ 4.3    drain 30% → eq ≈ 4.3 / 0.3  = 14
    //   DELIVERED accumulate ~4.2/tick = +126/min
    // Hasilnya: ratio PLACED:PAID:SHIPPED ≈ 4.4 : 1.6 : 1 — realistis e-commerce.
    private static final double DRAIN_PLACED  = 0.08;
    private static final double DRAIN_PAID    = 0.20;
    private static final double DRAIN_SHIPPED = 0.30;

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final Faker faker = new Faker();

    @Value("${datagen.new-customer-rate}")
    private double newCustomerRate;

    @Value("${datagen.order-per-tick}")
    private int orderPerTick;

    @Value("${datagen.status-update-rate}")
    private double statusUpdateRate;

    public DataGeneratorService(CustomerRepository customers,
                                ProductRepository products,
                                OrderRepository orders,
                                OrderItemRepository orderItems) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
        this.orderItems = orderItems;
    }

    @Scheduled(fixedDelayString = "${datagen.tick-interval-ms}")
    @Transactional
    public void tick() {
        try {
            if (ThreadLocalRandom.current().nextDouble() < newCustomerRate) {
                createCustomer();
            }
            for (int i = 0; i < orderPerTick; i++) {
                createOrder();
            }
            // Advance multiple orders per tick mengikuti state machine realistis
            // supaya funnel windowFunnel mendapat data PLACED→PAID→SHIPPED→DELIVERED.
            // Tidak pakai probability gate — pipeline progress yang konsisten.
            advanceOrderStatusBatch();
        } catch (Exception e) {
            log.error("tick failed", e);
        }
    }

    private void createCustomer() {
        Customer c = new Customer();
        c.setName(faker.name().fullName());
        c.setEmail(faker.internet().emailAddress());
        c.setCity(faker.address().city());
        customers.save(c);
        log.info("new customer id={} email={}", c.getId(), c.getEmail());
    }

    private void createOrder() {
        Optional<Customer> maybeCustomer = customers.pickRandom();
        if (maybeCustomer.isEmpty()) {
            createCustomer();
            return;
        }
        int itemCount = ThreadLocalRandom.current().nextInt(1, 4);
        List<Product> picked = products.pickRandom(itemCount);
        if (picked.isEmpty()) return;

        Order order = new Order();
        order.setCustomerId(maybeCustomer.get().getId());
        order.setStatus("PLACED");
        order.setTotalAmount(BigDecimal.ZERO);
        orders.save(order);

        BigDecimal total = BigDecimal.ZERO;
        for (Product p : picked) {
            int qty = ThreadLocalRandom.current().nextInt(1, 4);
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(p.getId());
            item.setQuantity(qty);
            item.setUnitPrice(p.getPrice());
            orderItems.save(item);
            total = total.add(p.getPrice().multiply(BigDecimal.valueOf(qty)));
        }
        order.setTotalAmount(total);
        orders.save(order);
        log.info("new order id={} items={} total={}", order.getId(), picked.size(), total);
    }

    private void advanceOrderStatus() {
        orders.pickActive().ifPresent(order -> {
            String next = NEXT_STATUSES.get(ThreadLocalRandom.current().nextInt(NEXT_STATUSES.size()));
            order.setStatus(next);
            orders.save(order);
            log.info("order id={} -> {}", order.getId(), next);
        });
    }

    /**
     * Advance per status dengan rate proporsional — meniru pipeline e-commerce
     * nyata. Drain rate = % of current queue, jadi equilibrium tercapai.
     * State machine transition:
     *   PLACED  → PAID    (90%) / CANCELLED (10%)  [drain  8%/tick]
     *   PAID    → SHIPPED (95%) / CANCELLED (5%)   [drain 20%/tick]
     *   SHIPPED → DELIVERED (99%) / CANCELLED (1%) [drain 30%/tick]
     */
    private void advanceOrderStatusBatch() {
        advanceProportional("PLACED",  DRAIN_PLACED);
        advanceProportional("PAID",    DRAIN_PAID);
        advanceProportional("SHIPPED", DRAIN_SHIPPED);
    }

    private void advanceProportional(String status, double drainRate) {
        long queueSize = orders.countByStatus(status);
        if (queueSize == 0) return;
        int n = Math.max(1, (int) Math.ceil(queueSize * drainRate));
        advanceList(orders.pickByStatus(status, n));
    }

    private void advanceList(List<Order> batch) {
        for (Order order : batch) {
            String current = order.getStatus();
            String next = nextStatusFor(current);
            if (next == null) continue;
            order.setStatus(next);
            orders.save(order);
            log.info("order id={} {} -> {}", order.getId(), current, next);
        }
    }

    private String nextStatusFor(String current) {
        double r = ThreadLocalRandom.current().nextDouble();
        return switch (current) {
            case "PLACED"  -> r < 0.90 ? "PAID"      : "CANCELLED";
            case "PAID"    -> r < 0.95 ? "SHIPPED"   : "CANCELLED";
            case "SHIPPED" -> r < 0.99 ? "DELIVERED" : "CANCELLED";
            default -> null;
        };
    }
}
