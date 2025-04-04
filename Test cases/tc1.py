# InsufficientWallet.py
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

def get_product(product_id):
    return requests.get(MARKETPLACE_SERVICE_URL + f"/products/{product_id}")

def post_order(user_id, items):
    payload = {"user_id": user_id, "items": items}
    return requests.post(MARKETPLACE_SERVICE_URL + "/orders", json=payload)

def main():
    user_id = 201
    name = "Alice"
    email = "alice@example.com"
    product_id = 101  # assuming product 101 exists in products.csv
    wallet_amount = 50  # intentionally low balance
    order_quantity = 2  # cost will exceed wallet balance

    # Create user
    r = create_user(user_id, name, email)
    if r.status_code != 201:
        print("User creation failed")
        return
    # Initialize wallet with insufficient funds
    put_wallet(user_id, "credit", wallet_amount)
    # Retrieve product details to calculate cost
    product_response = get_product(product_id)
    if product_response.status_code != 200:
        print("Product retrieval failed")
        return
    product = product_response.json()
    cost_per_item = product['price']
    expected_cost = cost_per_item * order_quantity
    print(f"Cost per item: {cost_per_item}, Order cost: {expected_cost}, Wallet balance: {wallet_amount}")
    
    # Attempt to place order
    order_payload = {"user_id": user_id, "items": [{"product_id": product_id, "quantity": order_quantity}]}
    order_response = post_order(user_id, order_payload["items"])
    print(f"Order response status: {order_response.status_code}, Body: {order_response.text}")
    if order_response.status_code == 400:
        print("Test Passed: Order rejected due to insufficient wallet balance.")
    else:
        print("Test Failed: Order should have been rejected.")

if __name__ == "__main__":
    main()
