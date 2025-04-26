#Concurrency1.py

import sys
import random
from threading import Thread, Lock
import requests

# Base URLs
USER_SERVICE_URL = "http://localhost:8080"
MARKETPLACE_SERVICE_URL = "http://localhost:8081"
WALLET_SERVICE_URL = "http://localhost:8082"

# Endpoints for the remaining functionalities
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

# Global counters for orders
successful_orders = 0
lock = Lock()

def place_order(user_id, product_id, quantity):
    global successful_orders
    # Each order attempts to buy "quantity" of the product
    response = post_order(user_id, [{"product_id": product_id, "quantity": quantity}])
    if response.status_code == 201:
        with lock:
            successful_orders += quantity
        print(f"[Thread] Order SUCCESS, response: {response.json()}")
    elif response.status_code == 400:
        # Order was rejected due to insufficient stock or wallet funds
        print(f"[Thread] Order FAILED with 400: {response.text}")
    else:
        print(f"[Thread] Unexpected status code: {response.status_code}, body: {response.text}")

def concurrency_order_test():
    """
    Test scenario:
      - A user is created and wallet credited with ample funds.
      - A given product (with known stock) is targeted.
      - Multiple threads attempt to place orders concurrently.
      - At the end, the total ordered quantity should not exceed the product's initial stock.
    """
    user_id = 503
    name = "Concurrent Tester"
    email = "concurraent@tst.com"
    product_id = 102
       # Ensure this product exists in your products.csv
    order_quantity = 1 # each order places quantity 1
    initial_wallet = 1000000  # plenty of funds

    # Get the product's initial stock and price
    prod_resp = get_product(product_id)
    if prod_resp.status_code != 200:
        print("Failed to get product details; aborting test.")
        sys.exit(1)
    product = prod_resp.json()
    initial_stock = product['stock_quantity']
    print(f"Product {product_id} initial stock: {initial_stock}")

    # Create user and credit wallet
    user_resp = create_user(user_id, name, email)
    if user_resp.status_code != 201:
        print("User creation failed; aborting test.")
        sys.exit(1)
    wallet_resp = put_wallet(user_id, "credit", initial_wallet)
    if wallet_resp.status_code != 200:
        print("Wallet credit failed; aborting test.")
        sys.exit(1)

    # Define number of threads and orders per thread (attempt to place more orders than available)
    thread_count = 10
    orders_per_thread = (initial_stock // order_quantity) + 5  # overshoot intentionally

    threads = []
    for i in range(thread_count):
        t = Thread(target=lambda: [place_order(user_id, product_id, order_quantity) for _ in range(orders_per_thread)])
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    # Final check of product stock after all order attempts
    final_prod_resp = get_product(product_id)
    if final_prod_resp.status_code != 200:
        print("Failed to retrieve product details after orders.")
        sys.exit(1)
    final_stock = final_prod_resp.json()['stock_quantity']
    print(f"Final product stock for product {product_id}: {final_stock}")
    total_ordered = successful_orders
    print(f"Total quantity ordered successfully: {total_ordered}")

    # Validate that the number of successfully ordered units does not exceed the initial stock.
    if total_ordered > initial_stock:
        print(f"Test FAILED: Ordered {total_ordered} exceeds available stock {initial_stock}.")
        sys.exit(1)
    else:
        print(f"Test PASSED: Ordered {total_ordered} within available stock {initial_stock}.")

if __name__ == "__main__":
    concurrency_order_test()
