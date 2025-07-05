/*
 * Simple E-Commerce Demo
 *
 * Notes:
 * - Shipping fee = base fee + rate-per-kg × total weight.
 * - Virtual items require no shipping.
 * - Stock is deducted only after successful payment.
 * - Customer balance is shown after checkout.
 * - edge cases: insufficient funds, empty cart, expired items.
 */

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class ECommerceApp {
    public static void main(String[] args) {
        new ECommerceApp().runDemo();
    }

    private void runDemo() {
        System.out.println("Welcome to the E-Commerce Demo!\n");

        // Scenario 1: successful purchase
        System.out.println("Scenario 1: Successful purchase");
        var cart1 = new Cart();
        var customer1 = new Customer("John Doe", 2000.0);
        cart1.add(new PerishableProduct("Cheese", 100, 5, LocalDate.now().plusDays(7), 0.2), 2);
        cart1.add(new VirtualProduct("Scratch Card", 50, 10), 3);
        cart1.add(new ShippableProduct("TV", 500, 2, 15.0), 1);
        executeCheckout(customer1, cart1);

        // Scenario 2: insufficient funds
        System.out.println("\nScenario 2: Insufficient funds");
        var cart2 = new Cart();
        var customer2 = new Customer("Jane Smith", 100.0);
        cart2.add(new PerishableProduct("Cheese", 100, 5, LocalDate.now().plusDays(7), 0.2), 4);
        executeCheckout(customer2, cart2);

        // Scenario 3: empty cart
        System.out.println("\nScenario 3: Empty cart");
        executeCheckout(new Customer("Empty Buyer", 500.0), new Cart());

        // Scenario 4: expired product
        System.out.println("\nScenario 4: Expired item");
        var cart4 = new Cart();
        var customer4 = new Customer("Expiry Tester", 100.0);
        cart4.add(new PerishableProduct("Old Milk", 20, 1, LocalDate.now().minusDays(1), 0.5), 1);
        executeCheckout(customer4, cart4);
        
        System.out.println("\nScenario 5: Virtual-only purchase");
        var cart5 = new Cart();
        var customer5 = new Customer("Virtual Lover", 200.0);
        cart5.add(new VirtualProduct("E-Book", 30, 10), 2);
        cart5.add(new VirtualProduct("Online Course", 100, 5), 1);
        executeCheckout(customer5, cart5);
    }

    /**
     * Perform checkout: process payment, ship items, print receipt
     */
    private void executeCheckout(Customer customer, Cart cart) {
        System.out.printf("Processing checkout for %s...%n", customer.getName());
        var shippingService = new StandardShippingService();
        var checkout = new CheckoutService(shippingService);
        try {
            checkout.process(customer, cart);
            System.out.printf("Done. Remaining balance: $%.2f%n", customer.getBalance());
        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
        }
    }
}

// ----- Domain -----

abstract class Product {
    private final String name;
    private final double price;
    private int stock;

    public Product(String name, double price, int stock) {
        this.name  = Objects.requireNonNull(name, "Name can't be null");
        this.price = price;
        this.stock = Math.max(0, stock);
    }

    public String getName() { return name; }
    public double getPrice() { return price; }
    public int getStock() { return stock; }

    public void reduceStock(int qty) {
        if (qty <= 0 || qty > stock) throw new IllegalArgumentException(
            String.format("Can't remove %d (only %d available)", qty, stock));
        stock -= qty;
    }

    public boolean isAvailable(int qty) {
        return qty > 0 && qty <= stock;
    }

    public abstract boolean isExpired();
    public abstract boolean requiresShipping();
}

interface Shippable {
    String getName();
    double getWeight();
}

class PerishableProduct extends Product implements Shippable {
    private final LocalDate expiry;
    private final double weightKg;

    public PerishableProduct(String name, double price, int stock,
                              LocalDate expiry, double weightKg) {
        super(name, price, stock);
        this.expiry = Objects.requireNonNull(expiry, "Expiry can't be null");
        this.weightKg = weightKg;
    }

    @Override public boolean isExpired() { return LocalDate.now().isAfter(expiry); }
    @Override public boolean requiresShipping() { return true; }
    @Override public double getWeight() { return weightKg; }
}

class ShippableProduct extends Product implements Shippable {
    private final double weightKg;

    public ShippableProduct(String name, double price, int stock, double weightKg) {
        super(name, price, stock);
        this.weightKg = weightKg;
    }

    @Override public boolean isExpired() { return false; }
    @Override public boolean requiresShipping() { return true; }
    @Override public double getWeight() { return weightKg; }
}

class VirtualProduct extends Product {
    public VirtualProduct(String name, double price, int stock) {
        super(name, price, stock);
    }
    @Override public boolean isExpired() { return false; }
    @Override public boolean requiresShipping() { return false; }
}

class CartItem {
    private final Product product;
    private final int quantity;

    public CartItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }

    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public double getTotal() { return product.getPrice() * quantity; }
}

class Cart {
    private final Map<Product, CartItem> items = new LinkedHashMap<>();

    public void add(Product p, int qty) {
        if (!p.isAvailable(qty)) throw new IllegalArgumentException(
            p.getName() + " out of stock");
        items.compute(p, (prod, exist) -> {
            int total = (exist == null ? 0 : exist.getQuantity()) + qty;
            if (!prod.isAvailable(total)) throw new IllegalArgumentException(
                "Not enough " + prod.getName());
            return new CartItem(prod, total);
        });
    }

    public void remove(Product p, int qty) {
        var exist = items.get(p);
        if (exist == null) throw new IllegalArgumentException(p.getName() + " not in cart");
        int have = exist.getQuantity();
        if (qty <= 0 || qty > have) throw new IllegalArgumentException(
            String.format("Cannot remove %d; only %d in cart", qty, have));
        if (qty == have) items.remove(p);
        else items.put(p, new CartItem(p, have - qty));
    }

    public List<CartItem> getItems() { return new ArrayList<>(items.values()); }
    public boolean isEmpty() { return items.isEmpty(); }
    public double subtotal() { return getItems().stream()
        .mapToDouble(CartItem::getTotal).sum(); }
    public void clear() { items.clear(); }
}

interface ShippingService {
    void ship(List<Shippable> items);
    double calculateFee(double totalWeightKg);
}

class StandardShippingService implements ShippingService {
    private static final double BASE = 8.0, RATE = 20.0;

    @Override
    public void ship(List<Shippable> items) {
        if (items.isEmpty()) return;
        System.out.println("-- Shipment Notice --");
        var groups = items.stream()
            .collect(Collectors.groupingBy(Shippable::getName));
        double totalWeight = 0;
        for (var entry : groups.entrySet()) {
            var name = entry.getKey();
            var list = entry.getValue();
            int count = list.size();
            double w = list.get(0).getWeight();
            System.out.println("Shipping " + count + "× " + name + " (" + (int)(w*count*1000) + "g)");
            totalWeight += w*count;
        }
        System.out.printf("Total weight: %.1f kg%n%n", totalWeight);
    }

    @Override
    public double calculateFee(double totalWeightKg) {
        return BASE + RATE * totalWeightKg;
    }
}

class Customer {
    private final String name;
    private double balance;

    public Customer(String name, double balance) {
        this.name = name;
        this.balance = balance;
    }
    public String getName() { return name; }
    public double getBalance() { return balance; }

    public void charge(double amt) {
        if (amt > balance) throw new IllegalStateException("Insufficient funds");
        balance -= amt;
    }
}

class CheckoutService {
    private final ShippingService shipSvc;
    public CheckoutService(ShippingService shipSvc) { this.shipSvc = shipSvc; }

    public void process(Customer cust, Cart cart) {
        if (cart.isEmpty()) throw new IllegalStateException("Cart cannot be empty");
        var toShip = new ArrayList<Shippable>();
        for (var ci : cart.getItems()) {
            var p = ci.getProduct();
            if (p.isExpired()) throw new IllegalStateException(p.getName()+" expired");
            if (p.requiresShipping()) for (int i=0;i<ci.getQuantity();i++) toShip.add((Shippable)p);
        }
        double sub = cart.subtotal();
        double fee = shipSvc.calculateFee(toShip.stream().mapToDouble(Shippable::getWeight).sum());
        double total = sub+fee;
        cust.charge(total);
        cart.getItems().forEach(ci->ci.getProduct().reduceStock(ci.getQuantity()));
        if (!toShip.isEmpty()) shipSvc.ship(toShip);
        printReceipt(cart, sub, fee, total);
        cart.clear();
    }

    private void printReceipt(Cart cart,double sub,double ship,double total) {
        System.out.println("-- Receipt --");
        cart.getItems().forEach(ci -> System.out.printf(
            "%dx %-14s $%.0f%n", ci.getQuantity(), ci.getProduct().getName(), ci.getTotal()));
        System.out.println("----------------");
        System.out.printf("Subtotal: $%.0f%nShipping: $%.0f%nTotal:    $%.0f%n%n", sub, ship, total);
    }
}
