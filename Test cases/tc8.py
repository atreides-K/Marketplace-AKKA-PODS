#Concurrency4.py

import sys
import random
from threading import Thread
import requests

from user import post_user
from wallet import put_wallet, get_wallet
from marketplace import post_order, get_product, test_get_product_stock, test_post_order
from utils import check_response_status_code, print_fail_message, print_pass_message

# Base URL for Marketplace is already used in get_product() and post_order()
# We'll track how many orders are successfully placed
successful_orders = 0

def place_order_thread(user_id, product_id, attempts=5):
    """
    Each thread attempts to place 'attempts' orders for the same product_id, each ordering quantity 1.
    On success (HTTP 201), the global counter 'successful_orders' is incremented.
    The response structure is verified via test_post_order().
    """
    global successful_orders
    for _ in range(attempts):
        resp = post_order(user_id, [{"product_id": product_id, "quantity": 1}])
        if resp.status_code == 201:
            # Verify success scenario without specifying expected_total_price
            if not test_post_order(user_id, items=[{"product_id": product_id, "quantity": 1}],
                                   response=resp, expect_success=True):
                print_fail_message("test_post_order failed on success scenario.")
            successful_orders += 1
        elif resp.status_code == 400:
            if not test_post_order(user_id, items=[{"product_id": product_id, "quantity": 1}],
                                   response=resp, expect_success=False):
                print_fail_message("test_post_order failed on expected failure scenario.")
        else:
            print_fail_message(f"Unexpected status code {resp.status_code} for POST /orders.")

def main():
    try:
        # Create a user with a large enough wallet balance to place many orders
        user_id = 8001
        resp = post_user(user_id, "Bob Market", "bob@market.com")
        if not check_response_status_code(resp, 201):
            return False

        # Credit the user's wallet significantly (e.g., 200000)
        resp = put_wallet(user_id, "credit", 240000)
        if not check_response_status_code(resp, 200):
            return False

        # Retrieve the target product's initial stock
        product_id = 105
        initial_stock = 12  # Adjust this value based on your products.csv
        resp = get_product(product_id)
        if resp.status_code != 200:
            print_fail_message("Failed to retrieve product details.")
            return False

        # Launch concurrency threads to place orders for the product (each ordering 1 unit)
        global successful_orders
        successful_orders = 0  # reset global counter

        thread_count = 3
        attempts_per_thread = 5  # total attempts = 15; available stock is only 10

        threads = []
        for i in range(thread_count):
            t = Thread(target=place_order_thread, kwargs={
                "user_id": user_id,
                "product_id": product_id,
                "attempts": attempts_per_thread
            })
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        print_pass_message(f"Total successful orders = {successful_orders}")

        if successful_orders > initial_stock:
            print_fail_message(
                f"Concurrency error: successful_orders ({successful_orders}) > available stock ({initial_stock})"
            )
            return False

        expected_final_stock = initial_stock - successful_orders

        # Verify final product stock via GET /products/{productId}
        resp = get_product(product_id)
        if not test_get_product_stock(product_id, resp, expected_stock=expected_final_stock):
            return False

        print_pass_message("Marketplace concurrency test passed.")
        return True

    except Exception as e:
        print_fail_message(f"Test crashed: {e}")
        return False

if __name__ == "__main__":
    if main():
        sys.exit(0)
    else:
        sys.exit(1)
