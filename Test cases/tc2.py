# DeliveredOrderCancellation.py
import time
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

def post_order(user_id, items):
    payload = {"user_id": user_id, "items": items}
    return requests.post(MARKETPLACE_SERVICE_URL + "/orders", json=payload)

def put_order(order_id, status):
    payload = {"order_id": order_id, "status": status}
    return requests.put(MARKETPLACE_SERVICE_URL + f"/orders/{order_id}", json=payload)

def delete_order(order_id):
    return requests.delete(MARKETPLACE_SERVICE_URL + f"/orders/{order_id}")

def main():
    user_id = 202
    name = "Bob"
    email = "bob@example.com"
    product_id = 102  # ensure this exists in products.csv
    wallet_amount = 45000  # ample funds
    order_quantity = 1

    # Create user and initialize wallet
    r = create_user(user_id, name, email)
    if r.status_code != 201:
        print("User creation failed")
        return
    time.sleep(2.5)
    put_wallet(user_id, "credit", wallet_amount)

    # Place an order successfully
    time.sleep(2.5)
    order_payload = {"user_id": user_id, "items": [{"product_id": product_id, "quantity": order_quantity}]}
    order_response = post_order(user_id, order_payload["items"])
    if order_response.status_code != 201:
        print("Order placement failed")
        return
    order_id = order_response.json()['order_id']
    print(f"Order placed with order_id: {order_id}")

    # Mark the order as delivered
    delivered_response = put_order(order_id, "DELIVERED")
    print(f"PUT order delivered response: {delivered_response.status_code}, {delivered_response.text}")

    # Attempt to cancel the delivered order
    cancel_response = delete_order(order_id)
    print(f"Attempted cancellation response: {cancel_response.status_code}, {cancel_response.text}")

    if cancel_response.status_code == 400:
        print("Test Passed: Delivered order cancellation rejected as expected.")
    else:
        print("Test Failed: Delivered order should not be cancellable.")

if __name__ == "__main__":
    main()
