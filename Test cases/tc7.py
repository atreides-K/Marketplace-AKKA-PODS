#Concurrency3.py

import sys
import random
import time
from threading import Thread, Lock
import requests

# Base URLs for allowed endpoints
USER_SERVICE_URL = "http://localhost:8080"
MARKETPLACE_SERVICE_URL = "http://localhost:8081"
WALLET_SERVICE_URL = "http://localhost:8082"

def create_user(user_id, name, email):
    payload = {"id": user_id, "name": name, "email": email}
    return requests.post(f"{USER_SERVICE_URL}/users", json=payload)

def put_wallet(user_id, action, amount):
    payload = {"action": action, "amount": amount}
    return requests.put(f"{WALLET_SERVICE_URL}/wallets/{user_id}", json=payload)

def get_wallet(user_id):
    return requests.get(f"{WALLET_SERVICE_URL}/wallets/{user_id}")

def get_product(product_id):
    return requests.get(f"{MARKETPLACE_SERVICE_URL}/products/{product_id}")

def post_order(user_id, items):
    payload = {"user_id": user_id, "items": items}
    return requests.post(f"{MARKETPLACE_SERVICE_URL}/orders", json=payload)

def put_order(order_id, status):
    payload = {"order_id": order_id, "status": status}
    return requests.put(f"{MARKETPLACE_SERVICE_URL}/orders/{order_id}", json=payload)

def delete_order(order_id):
    return requests.delete(f"{MARKETPLACE_SERVICE_URL}/orders/{order_id}")

# Shared list for successful order IDs with a lock
order_ids = []
order_ids_lock = Lock()

def place_order_thread(user_id, product_id, iterations=10):
    """
    Each thread places orders of 1 unit for the given product.
    On success (HTTP 201), the order_id is added to the global list.
    """
    for _ in range(iterations):
        response = post_order(user_id, [{"product_id": product_id, "quantity": 1}])
        if response.status_code == 201:
            order = response.json()
            with order_ids_lock:
                order_ids.append(order['order_id'])
            print(f"[PlaceOrder] SUCCESS: Order {order['order_id']} placed.")
        elif response.status_code == 400:
            print(f"[PlaceOrder] Order rejected (400): {response.text}")
        else:
            print(f"[PlaceOrder] Unexpected response {response.status_code}: {response.text}")
        time.sleep(random.uniform(0.01, 0.05))

def cancel_order_thread(iterations=10):
    """
    Each thread attempts to cancel orders by taking an order_id from the shared list.
    """
    for _ in range(iterations):
        order_id = None
        with order_ids_lock:
            if order_ids:
                order_id = order_ids.pop(0)
        if order_id is not None:
            response = delete_order(order_id)
            if response.status_code == 200:
                print(f"[CancelOrder] SUCCESS: Order {order_id} canceled.")
            elif response.status_code == 400:
                print(f"[CancelOrder] Cancel rejected (400) for Order {order_id}: {response.text}")
            else:
                print(f"[CancelOrder] Unexpected response for Order {order_id}: {response.status_code}, {response.text}")
        else:
            print("[CancelOrder] No order available to cancel.")
        time.sleep(random.uniform(0.01, 0.05))

def concurrent_order_cancellation_test():
    """
    Test scenario:
      1. Create a user and credit the wallet.
      2. Retrieve a product's initial stock and price.
      3. Spawn multiple threads to concurrently place orders (each ordering 1 unit).
      4. Spawn multiple threads to concurrently cancel some of the orders.
      5. Finally, verify that the final product stock is as expected and that the wallet balance
         reflects refunded amounts from cancellations.
    """
    user_id = 7001
    name = "Concurrent Tester"
    email = "concurrent2@test.com"
    product_id = 108  # Adjust based on products.csv
    initial_wallet = 50000
    order_quantity = 1  # Each order orders one unit

    # Step 1: Create user and credit wallet.
    print("Creating user...")
    user_resp = create_user(user_id, name, email)
    if user_resp.status_code != 201:
        print("User creation failed; aborting test.")
        sys.exit(1)
    wallet_resp = put_wallet(user_id, "credit", initial_wallet)
    if wallet_resp.status_code != 200:
        print("Wallet credit failed; aborting test.")
        sys.exit(1)

    # Step 2: Get initial product details.
    prod_resp = get_product(product_id)
    if prod_resp.status_code != 200:
        print("Failed to retrieve product details; aborting test.")
        sys.exit(1)
    product = prod_resp.json()
    initial_stock = product['stock_quantity']
    product_price = product['price']
    print(f"Product {product_id} initial stock: {initial_stock}, price: {product_price}")

    # Step 3: Launch threads to place orders concurrently.
    place_threads = []
    num_place_threads = 5
    orders_per_thread = (initial_stock // order_quantity) + 3  # overshoot intentionally

    for i in range(num_place_threads):
        t = Thread(target=place_order_thread, kwargs={
            "user_id": user_id,
            "product_id": product_id,
            "iterations": orders_per_thread
        })
        place_threads.append(t)
        t.start()
    for t in place_threads:
        t.join()

    print(f"Total orders placed (successful order IDs collected): {len(order_ids)}")

    # Step 4: Launch threads to cancel orders concurrently.
    cancel_threads = []
    num_cancel_threads = 3
    cancellations_per_thread = 5

    for i in range(num_cancel_threads):
        t = Thread(target=cancel_order_thread, kwargs={"iterations": cancellations_per_thread})
        cancel_threads.append(t)
        t.start()
    for t in cancel_threads:
        t.join()

    # Wait briefly to let cancellations propagate.
    time.sleep(1)

    # Step 5: Final check of product stock.
    final_prod_resp = get_product(product_id)
    if final_prod_resp.status_code != 200:
        print("Failed to retrieve product details after cancellations; aborting test.")
        sys.exit(1)
    final_stock = final_prod_resp.json()['stock_quantity']
    print(f"Final product stock for product {product_id}: {final_stock}")

    # Since cancellations roll back the product stock, expected stock = initial_stock - (orders not canceled)
    remaining_orders = len(order_ids)
    expected_stock = initial_stock - remaining_orders
    if final_stock == expected_stock:
        print(f"Test PASSED: Final stock {final_stock} as expected (initial {initial_stock} minus remaining {remaining_orders}).")
    else:
        print(f"Test FAILED: Final stock {final_stock} does not match expected {expected_stock}.")

    # Check wallet balance.
    # Note: Cancellations refund the order cost, so wallet balance should be at least:
    #   initial_wallet - (cost of orders that were not canceled)
    # Here, cost per order is product_price (non-discounted for simplicity since discount only applies once).
    remaining_cost = remaining_orders * product_price
    wallet_after = get_wallet(user_id).json()['balance']
    min_expected_balance = initial_wallet - remaining_cost
    print(f"Wallet balance after cancellations: {wallet_after}, minimum expected balance: {min_expected_balance}")
    if wallet_after >= min_expected_balance:
        print("Wallet balance check PASSED.")
    else:
        print("Wallet balance check FAILED.")

if __name__ == "__main__":
    concurrent_order_cancellation_test()
