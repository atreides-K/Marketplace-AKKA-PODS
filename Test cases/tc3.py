# OrderCancellation.py
import requests

USER_SERVICE_URL = "http://localhost:8080"
MARKETPLACE_SERVICE_URL = "http://localhost:8081"
WALLET_SERVICE_URL = "http://localhost:8082"

def create_user(user_id, name, email):
    payload = {"id": user_id, "name": name, "email": email}
    return requests.post(USER_SERVICE_URL + "/users", json=payload)

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

def delete_order(order_id):
    return requests.delete(MARKETPLACE_SERVICE_URL + f"/orders/{order_id}")

def main():
    user_id = 203
    name = "Carol"
    email = "carol@example.com"
    product_id = 103  # choose an appropriate product from products.csv
    wallet_amount = 9000
    order_quantity = 3

    # Create user and set wallet
    r = create_user(user_id, name, email)
    if r.status_code != 201:
        print("User creation failed")
        return
    put_wallet(user_id, "credit", wallet_amount)
    
    # Get initial wallet and product stock details
    
    product_before = get_product(product_id).json()['stock_quantity']

    # Place an order
    order_payload = {"user_id": user_id, "items": [{"product_id": product_id, "quantity": order_quantity}]}
    order_response = post_order(user_id, order_payload["items"])
    if order_response.status_code != 201:
        print("Order placement failed")
        return
    order_id = order_response.json()['order_id']
    print(f"Order placed with order_id: {order_id}")

    wallet_before = get_wallet(user_id).json()['balance']

    # Cancel the order
    cancel_response = delete_order(order_id)
    print(f"Cancellation response: {cancel_response.status_code}, {cancel_response.text}")
    if cancel_response.status_code != 200:
        print("Test Failed: Order cancellation failed.")
        return

    # Verify that the wallet has been refunded and product stock restored
    wallet_after = get_wallet(user_id).json()['balance']
    product_after = get_product(product_id).json()['stock_quantity']
    
    # Calculate expected refund (assume a 10% discount is applied on first order)
    product_price = get_product(product_id).json()['price']
    order_cost = product_price * order_quantity * 0.9

    if (wallet_after - wallet_before) == order_cost and (product_after - product_before) == 0:
        print("Test Passed: Wallet refund and stock rollback successful.")
    else:
        print("Test Failed: Refund or stock rollback did not occur as expected.")
        print(f"Wallet before: {wallet_before}, after: {wallet_after}, expected refund: {order_cost}")
        print(f"Product stock before: {product_before}, after: {product_after}")

if __name__ == "__main__":
    main()
