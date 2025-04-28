import sys
import csv
import os
import threading

from user import post_user
from wallet import put_wallet, get_wallet
from marketplace import post_order
from utils import (
    print_fail_message,
    print_pass_message,
    check_response_status_code,
)

PRODUCTS_CSV = "products.csv"

# We will test these 10 product pairs (each "order" tries to buy full stock for exactly these two products):
PRODUCT_PAIRS = [
    (101, 102),
    (103, 104),
    (105, 106),
    (107, 108),
    (109, 110),
    (111, 112),
    (113, 114),
    (115, 116),
    (117, 118),
    (119, 120)
]

def read_products_from_csv(csv_file):
    """
    Reads product data from CSV (id,price,stock_quantity)
    and returns a dict:
      {
        101: {"price": 55000, "stock": 10},
        102: {"price": 45000, "stock": 8},
        ...
      }
    """
    product_dict = {}
    if not os.path.exists(csv_file):
        print_fail_message(f"Products CSV file '{csv_file}' not found.")
        return product_dict

    with open(csv_file, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            p_id = int(row["id"])
            price = int(row["price"])
            stock_qty = int(row["stock_quantity"])
            product_dict[p_id] = {
                "price": price,
                "stock": stock_qty
            }
    return product_dict

def compute_pair_cost(p1, p2, product_info):
    """
    For products p1, p2, return:
       (price_of_p1 * stock_of_p1) + (price_of_p2 * stock_of_p2)
    """
    return (
        product_info[p1]["price"] * product_info[p1]["stock"] +
        product_info[p2]["price"] * product_info[p2]["stock"]
    )

class UserOrderThread(threading.Thread):
    """
    Each thread corresponds to a single user,
    and attempts 10 orders (one for each product pair).
    We store each response in self.responses.
    """
    def __init__(self, user_id, product_info, thread_name=""):
        super().__init__(name=thread_name)
        self.user_id = user_id
        self.product_info = product_info
        self.responses = []

    def run(self):
        """
        For each of the 10 product pairs, place an order
        with the maximum stock for those two products.
        """
        for (p1, p2) in PRODUCT_PAIRS:
            items = [
                {"product_id": p1, "quantity": self.product_info[p1]["stock"]},
                {"product_id": p2, "quantity": self.product_info[p2]["stock"]}
            ]
            resp = post_order(self.user_id, items)
            self.responses.append(resp)
def main():
    try:
        # 1. Read product info from CSV
        product_info = read_products_from_csv(PRODUCTS_CSV)
        if not product_info:
            print_fail_message("No products loaded from CSV. Aborting test.")
            return False

        # 2. Calculate the total cost if a user "won" all 10 orders (i.e. all pairs).
        total_needed = 0
        for (p1, p2) in PRODUCT_PAIRS:
            total_needed += compute_pair_cost(p1, p2, product_info)

        # 3. Create two users
        user1_id = 300001
        user2_id = 300002

        resp1 = post_user(user1_id, "UserThree001", "u300001@example.com")
        if not check_response_status_code(resp1, 201):
            return False

        resp2 = post_user(user2_id, "UserThree002", "u300002@example.com")
        if not check_response_status_code(resp2, 201):
            return False

        # 4. Credit each user enough that they COULD buy all 10 orders if they "won" each attempt
        resp1c = put_wallet(user1_id, "credit", total_needed)
        if not check_response_status_code(resp1c, 200):
            return False

        resp2c = put_wallet(user2_id, "credit", total_needed)
        if not check_response_status_code(resp2c, 200):
            return False

        # 5. Create concurrency threads
        user1_thread = UserOrderThread(user1_id, product_info, thread_name="User1Thread")
        user2_thread = UserOrderThread(user2_id, product_info, thread_name="User2Thread")

        # 6. Start them
        user1_thread.start()
        user2_thread.start()

        # 7. Wait for them to finish
        user1_thread.join()
        user2_thread.join()

        # 8. Analyze the results
        user1_total_spent = 0
        user2_total_spent = 0
        user1_first_success_index = -1 # <<< Track index of first success
        user2_first_success_index = -1 # <<< Track index of first success

        # First pass: Check for double success and find the first success index for each user
        for i in range(10):
            resp_u1 = user1_thread.responses[i]
            resp_u2 = user2_thread.responses[i]

            success_u1 = (resp_u1.status_code == 201)
            success_u2 = (resp_u2.status_code == 201)

            if success_u1 and success_u2:
                print_fail_message(f"Order #{i+1} succeeded for BOTH users! That shouldn't happen.")
                return False

            if success_u1 and user1_first_success_index == -1: # <<< Store index of first success
                 user1_first_success_index = i

            if success_u2 and user2_first_success_index == -1: # <<< Store index of first success
                 user2_first_success_index = i

        # Second pass: Calculate total spent, applying discount for the first success
        # <<< Recalculate spent amount considering the discount
        for i in range(10):
            resp_u1 = user1_thread.responses[i]
            resp_u2 = user2_thread.responses[i]

            if resp_u1.status_code == 201:
                p1, p2 = PRODUCT_PAIRS[i]
                cost = compute_pair_cost(p1, p2, product_info)
                if i == user1_first_success_index: # <<< Apply discount only if it's the first success
                    cost *= 0.9
                user1_total_spent += int(cost) # <<< Add discounted or full cost (use int if necessary)

            if resp_u2.status_code == 201:
                p1, p2 = PRODUCT_PAIRS[i]
                cost = compute_pair_cost(p1, p2, product_info)
                if i == user2_first_success_index: # <<< Apply discount only if it's the first success
                    cost *= 0.9
                user2_total_spent += int(cost) # <<< Add discounted or full cost (use int if necessary)


        # 9. Check each user's final wallet balance
        w1_resp = get_wallet(user1_id)
        w2_resp = get_wallet(user2_id)

        if w1_resp.status_code != 200 or w2_resp.status_code != 200:
            print_fail_message("Could not retrieve wallet balance for one or both users.")
            return False

        # parse final balances
        final_balance_u1 = w1_resp.json().get("balance", None)
        final_balance_u2 = w2_resp.json().get("balance", None)

        # expected final = total_needed - whatever user spent (now correctly calculated)
        expected_balance_u1 = total_needed - user1_total_spent
        expected_balance_u2 = total_needed - user2_total_spent

        if final_balance_u1 != expected_balance_u1:
            print(f"Debug: User 1 First Success Index: {user1_first_success_index}") # Optional debug
            print(f"Debug: User 1 Calculated Spent: {user1_total_spent}")        # Optional debug
            print_fail_message(
                f"User1 final balance mismatch. Expected {expected_balance_u1}, got {final_balance_u1}"
            )
            return False
        else: # <<< Added else for clarity
             print_pass_message(f"User1 final balance matches: {final_balance_u1}")

        if final_balance_u2 != expected_balance_u2:
            print(f"Debug: User 2 First Success Index: {user2_first_success_index}") # Optional debug
            print(f"Debug: User 2 Calculated Spent: {user2_total_spent}")        # Optional debug
            print_fail_message(
                f"User2 final balance mismatch. Expected {expected_balance_u2}, got {final_balance_u2}"
            )
            return False
        else: # <<< Added else for clarity
            print_pass_message(f"User2 final balance matches: {final_balance_u2}")


        print_pass_message("Concurrent test (two products per order) passed!")
        return True

    except Exception as e:
        print_fail_message(f"Test crashed with exception: {e}")
        import traceback # <<< Import traceback
        traceback.print_exc() # <<< Print stack trace for exceptions
        return False

if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)