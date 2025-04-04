import requests

# Base URLs for services
USER_SERVICE_URL = "http://localhost:8080"
MARKETPLACE_SERVICE_URL = "http://localhost:8081"
WALLET_SERVICE_URL = "http://localhost:8082"

def create_user(user_id, name, email):
    payload = {"id": user_id, "name": name, "email": email}
    response = requests.post(USER_SERVICE_URL + "/users", json=payload)
    return response

def get_user(user_id):
    return requests.get(USER_SERVICE_URL + f"/users/{user_id}")

def put_wallet(user_id, action, amount):
    payload = {"action": action, "amount": amount}
    return requests.put(WALLET_SERVICE_URL + f"/wallets/{user_id}", json=payload)

def get_wallet(user_id):
    return requests.get(WALLET_SERVICE_URL + f"/wallets/{user_id}")

def get_product(product_id):
    return requests.get(MARKETPLACE_SERVICE_URL + f"/products/{product_id}")

def post_order(user_id, items):
    payload = {"user_id": user_id, "items": items}
    return requests.post(MARKETPLACE_SERVICE_URL + "/orders", json=payload)

def put_order(order_id, status):
    payload = {"order_id": order_id, "status": status}
    return requests.put(MARKETPLACE_SERVICE_URL + f"/orders/{order_id}", json=payload)

def delete_order(order_id):
    return requests.delete(MARKETPLACE_SERVICE_URL + f"/orders/{order_id}")

def print_result(test_name, passed, details=""):
    status = "PASSED" if passed else "FAILED"
    print(f"[{test_name}] {status}. {details}")

def test_rigorous():
    # Use a unique user id for the test
    user_id = 300
    name = "Test User"
    email = "testuser@example.com"
    product_id = 117  # Make sure this product exists in products.csv
    initial_wallet = 11400

    # 1. Create a user and initialize wallet
    r = create_user(user_id, name, email)
    if r.status_code != 201:
        print_result("User Creation", False, f"Expected 201, got {r.status_code}")
        return
    print_result("User Creation", True)
    
    r_wallet = put_wallet(user_id, "credit", initial_wallet)
    if r_wallet.status_code != 200:
        print_result("Wallet Initialization", False, f"Expected 200, got {r_wallet.status_code}")
        return
    print_result("Wallet Initialization", True)
    
    # 2. Retrieve product details (to get price and stock)
    r_product = get_product(product_id)
    if r_product.status_code != 200:
        print_result("Get Product", False, f"Expected 200, got {r_product.status_code}")
        return
    product = r_product.json()
    product_price = product['price']
    initial_stock = product['stock_quantity']
    print_result("Get Product", True, f"Price: {product_price}, Stock: {initial_stock}")

    # 3. Place first order (should get 10% discount as first order)
    order_qty = 2
    r_order1 = post_order(user_id, [{"product_id": product_id, "quantity": order_qty}])
    if r_order1.status_code != 201:
        print_result("First Order Placement", False, f"Expected 201, got {r_order1.status_code}")
        return
    order1 = r_order1.json()
    order1_id = order1['order_id']
    expected_cost1 = product_price * order_qty * 0.9  # 10% discount
    print_result("First Order Placement", True, f"Order ID: {order1_id}, Expected Cost: {expected_cost1}")

    # Check wallet balance and product stock after first order
    wallet_after_order1 = get_wallet(user_id).json()['balance']
    product_after_order1 = get_product(product_id).json()['stock_quantity']
    if wallet_after_order1 != initial_wallet - expected_cost1:
        print_result("Wallet Update After First Order", False, f"Expected balance: {initial_wallet - expected_cost1}, got: {wallet_after_order1}")
    else:
        print_result("Wallet Update After First Order", True)
    if product_after_order1 != initial_stock - order_qty:
        print_result("Stock Deduction After First Order", False, f"Expected stock: {initial_stock - order_qty}, got: {product_after_order1}")
    else:
        print_result("Stock Deduction After First Order", True)

    # 4. Place second order (no discount)
    r_order2 = post_order(user_id, [{"product_id": product_id, "quantity": order_qty}])
    if r_order2.status_code != 201:
        print_result("Second Order Placement", False, f"Expected 201, got {r_order2.status_code}")
        return
    order2 = r_order2.json()
    order2_id = order2['order_id']
    expected_cost2 = product_price * order_qty  # no discount this time
    print_result("Second Order Placement", True, f"Order ID: {order2_id}, Expected Cost: {expected_cost2}")

    # Check wallet and stock after second order
    wallet_after_order2 = get_wallet(user_id).json()['balance']
    product_after_order2 = get_product(product_id).json()['stock_quantity']
    expected_wallet = initial_wallet - expected_cost1 - expected_cost2
    if wallet_after_order2 != expected_wallet:
        print_result("Wallet Update After Second Order", False, f"Expected balance: {expected_wallet}, got: {wallet_after_order2}")
    else:
        print_result("Wallet Update After Second Order", True)
    if product_after_order2 != initial_stock - order_qty * 2:
        print_result("Stock Deduction After Second Order", False, f"Expected stock: {initial_stock - order_qty*2}, got: {product_after_order2}")
    else:
        print_result("Stock Deduction After Second Order", True)

    # 5. Cancel the first order (which is still PLACED)
    r_cancel1 = delete_order(order1_id)
    if r_cancel1.status_code != 200:
        print_result("Order Cancellation (PLACED Order)", False, f"Expected 200, got: {r_cancel1.status_code}")
    else:
        print_result("Order Cancellation (PLACED Order)", True)
    # Verify wallet refund and stock rollback after cancellation
    wallet_after_cancel1 = get_wallet(user_id).json()['balance']
    product_after_cancel1 = get_product(product_id).json()['stock_quantity']
    # After cancellation, refund expected_cost1 back and product stock should be increased by order_qty
    expected_wallet_after_cancel = wallet_after_order2 + expected_cost1
    expected_stock_after_cancel = product_after_order2 + order_qty
    if wallet_after_cancel1 != expected_wallet_after_cancel:
        print_result("Wallet Refund After Cancellation", False, f"Expected balance: {expected_wallet_after_cancel}, got: {wallet_after_cancel1}")
    else:
        print_result("Wallet Refund After Cancellation", True)
    if product_after_cancel1 != expected_stock_after_cancel:
        print_result("Stock Rollback After Cancellation", False, f"Expected stock: {expected_stock_after_cancel}, got: {product_after_cancel1}")
    else:
        print_result("Stock Rollback After Cancellation", True)

    # 6. Mark second order as DELIVERED and then attempt cancellation (should fail)
    r_deliver = put_order(order2_id, "DELIVERED")
    if r_deliver.status_code != 200:
        print_result("Mark Order Delivered", False, f"Expected 200, got: {r_deliver.status_code}")
    else:
        print_result("Mark Order Delivered", True)
    r_cancel2 = delete_order(order2_id)
    if r_cancel2.status_code != 400:
        print_result("Cancellation of Delivered Order", False, f"Expected 400, got: {r_cancel2.status_code}")
    else:
        print_result("Cancellation of Delivered Order", True)

    # 7. Attempt to place an order with insufficient funds
    # First, reduce wallet balance intentionally:
    r_wallet_debit = put_wallet(user_id, "debit", get_wallet(user_id).json()['balance'])
    # Now wallet should be 0; try to place an order
    r_insufficient_funds = post_order(user_id, [{"product_id": product_id, "quantity": 1}])
    if r_insufficient_funds.status_code != 400:
        print_result("Order with Insufficient Funds", False, f"Expected 400, got: {r_insufficient_funds.status_code}")
    else:
        print_result("Order with Insufficient Funds", True)

    # 8. Attempt to place an order with quantity greater than available stock
    # First, top-up wallet again:
    put_wallet(user_id, "credit", 6000)
    # Now, try ordering a very high quantity
    r_insufficient_stock = post_order(user_id, [{"product_id": product_id, "quantity": 10000}])
    if r_insufficient_stock.status_code != 400:
        print_result("Order with Insufficient Stock", False, f"Expected 400, got: {r_insufficient_stock.status_code}")
    else:
        print_result("Order with Insufficient Stock", True)

if __name__ == "__main__":
    test_rigorous()
