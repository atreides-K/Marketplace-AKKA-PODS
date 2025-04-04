#Concurrency2.py

import sys
import random
from threading import Thread, Lock
import requests
import time

# Base URLs for the remaining endpoints
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

def delete_order(order_id):
    return requests.delete(f"{MARKETPLACE_SERVICE_URL}/orders/{order_id}")

# Global shared list for successful order IDs and a lock for synchronization
order_ids = []
order_ids_lock = Lock()

def place_order_thread(user_id, product_id, iterations=10):
    """
    Each thread attempts to place 'iterations' orders of 1 unit each.
    On success (HTTP 201), the order_id is stored in the global list.
    """
    for _ in range(iterations):
        resp = post_order(user_id, [{"product_id": product_id, "quantity": 1}])
        if resp.status_code == 201:
            order = resp.json()
            with order_ids_lock:
                order_ids.append(order['order_id'])
            print(f"[PlaceOrder] Success: Order ID {order['order_id']} placed.")
        elif resp.status_code == 400:
            # Likely due to insufficient stock or funds; log and continue.
            print(f"[PlaceOrder] Order failed (400): {resp.text}")
        else:
            print(f"[PlaceOrder] Unexpected response: {resp.status_code}, {resp.text}")
        # Sleep a short random time to simulate delay
        time.sleep(random.uniform(0.01, 0.05))

def cancel_order_thread(iterations=10):
    """
    Each thread attempts to cancel orders from the global order_ids list.
    It picks an order ID (if available) and calls DELETE /orders/{orderId}.
    """
    for _ in range(iterations):
        order_id = None
        with order_ids_lock:
            if order_ids:
                order_id = order_ids.pop(0)
        if order_id is not None:
            resp = delete_order(order_id)
            if resp.status_code == 200:
                print(f"[CancelOrder] Success: Order ID {order_id} canceled.")
            elif resp.status_code == 400:
                print(f"[CancelOrder] Cancel failed (400) for Order ID {order_id}: {resp.text}")
            else:
                print(f"[CancelOrder] Unexpected response for Order ID {order_id}: {resp.status_code}, {resp.text}")
        else:
            # No orders left to cancel
            print("[CancelOrder] No order available to cancel.")
        # Short delay between cancellations
        time.sleep(random.uniform(0.01, 0.05))

def concurrent_order_cancellation_test():
    """
    Test scenario:
      1. Create a user and credit the wallet.
      2. Retrieve product details for a given product.
      3. Launch several threads to concurrently place orders (each ordering one unit).
      4. Launch several threads to concurrently cancel orders.
      5. Finally, verify that:
           - The product stock is restored to its initial value.
           - The wallet balance is as expected (refund equals the cost of canceled orders).
    """
    user_id = 6001
    name = "Concurrent Canceler"
    email = "canceler@example.com"
    product_id = 119   # Ensure this product exists in products.csv
    initial_wallet = 50000  # Ample funds for ordering
    order_unit = 1         # each order places 1 unit

    # Step 1: Create user and credit wallet
    print("Creating user and crediting wallet...")
    user_resp = create_user(user_id, name, email)
    if user_resp.status_code != 201:
        print("User creation failed; aborting test.")
        sys.exit(1)
    wallet_resp = put_wallet(user_id, "credit", initial_wallet)
    if wallet_resp.status_code != 200:
        print("Wallet credit failed; aborting test.")
        sys.exit(1)

    # Step 2: Get initial product details
    prod_resp = get_product(product_id)
    if prod_resp.status_code != 200:
        print("Failed to get product details; aborting test.")
        sys.exit(1)
    product = prod_resp.json()
    initial_stock = product['stock_quantity']
    product_price = product['price']
    print(f"Product {product_id} initial stock: {initial_stock}, price: {product_price}")

    # Step 3: Launch threads to place orders concurrently.
    place_threads = []
    thread_count_place = 5
    orders_per_thread = (initial_stock // order_unit) + 3  # intentionally overshoot stock

    for i in range(thread_count_place):
        t = Thread(target=place_order_thread, kwargs={"user_id": user_id, "product_id": product_id, "iterations": orders_per_thread})
        place_threads.append(t)
        t.start()

    for t in place_threads:
        t.join()

    print(f"Total orders placed (successful orders collected): {len(order_ids)}")

    # Step 4: Launch threads to cancel orders concurrently.
    cancel_threads = []
    thread_count_cancel = 3
    cancellations_per_thread = 5

    for i in range(thread_count_cancel):
        t = Thread(target=cancel_order_thread, kwargs={"iterations": cancellations_per_thread})
        cancel_threads.append(t)
        t.start()

    for t in cancel_threads:
        t.join()

    # Allow some time for cancellation processing to complete
    time.sleep(1)

    # Step 5: Final checks
    final_prod_resp = get_product(product_id)
    if final_prod_resp.status_code != 200:
        print("Failed to get product details after cancellations; aborting test.")
        sys.exit(1)
    final_stock = final_prod_resp.json()['stock_quantity']
    print(f"Final product stock for product {product_id}: {final_stock}")

    # Since cancellations refund the order and rollback the stock,
    # the final stock should be initial_stock minus (orders not canceled).
    # In our ideal scenario, if all placed orders were canceled, final_stock == initial_stock.
    # Otherwise, final_stock = initial_stock - (number of orders still successfully executed and not canceled)
    orders_remaining = len(order_ids)
    expected_stock = initial_stock - orders_remaining
    if final_stock == expected_stock:
        print(f"Test PASSED: Final stock {final_stock} as expected (initial {initial_stock} minus remaining {orders_remaining}).")
    else:
        print(f"Test FAILED: Final stock {final_stock} does not match expected {expected_stock}.")

    # Also check wallet balance.
    # For each canceled order, refund equals order_unit * product_price * discount_factor.
    # Note: Only the first order for a user gets discount; subsequent orders use full price.
    # Since our test is concurrent and cancellations are random, we cannot predict the exact refund.
    # Instead, we can at least ensure that the wallet balance is no less than the initial_wallet minus the cost of orders that were not canceled.
    remaining_cost = orders_remaining * product_price  # assuming non-discounted cost for remaining orders
    wallet_after = get_wallet(user_id).json()['balance']
    min_expected_balance = initial_wallet - remaining_cost
    print(f"Wallet balance after cancellations: {wallet_after}, minimum expected balance: {min_expected_balance}")
    if wallet_after >= min_expected_balance:
        print("Wallet balance check PASSED.")
    else:
        print("Wallet balance check FAILED.")

if __name__ == "__main__":
    concurrent_order_cancellation_test()
