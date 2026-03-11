# MasonXPay

> There is no Java version of payment orchestrate system, let's vibe coding one.

This project is a fully-featured, Stripe-like payment gateway built with Java and Spring Boot. It provides a robust and scalable solution for processing payments, managing merchants, and routing transactions through various payment providers.

## Features

*   **Multi-Provider Routing:** Intelligently route payments to different providers (e.g., Stripe, PayPal) based on configurable rules.
*   **Complete Payment Lifecycle:** Supports the entire payment flow, including authorization, capture, refunds, and chargebacks.
*   **Merchant Management:** Onboard and manage merchants, including KYB (Know Your Business) verification.
*   **Role-Based Access Control (RBAC):** A comprehensive user and authentication system with distinct roles and permissions for merchant accounts.
*   **Developer-Friendly API:** A well-documented API for seamless integration with merchant websites and applications.
*   **Webhook Event Delivery:** Asynchronously deliver events to merchant-configured endpoints with retry mechanisms.
*   **Merchant Dashboard:** A user-friendly interface for merchants to view payments, manage settings, and access analytics.
*   **TypeScript SDK:** Provides both browser and server-side SDKs for easy integration.

## Tech Stack

*   **Backend:** Java 21, Spring Boot 3, Spring Security, Spring Data JPA
*   **Database:** PostgreSQL
*   **Migrations:** Flyway
*   **Authentication:** JWT (JSON Web Tokens)
*   **Payment Providers:** Stripe (with support for others like PayPal)
*   **Frontend (Dashboard):** Next.js, shadcn/ui, Tailwind CSS

## Prerequisites

*   Java 21
*   Maven
*   PostgreSQL
*   An account with a payment provider (e.g., Stripe)

## Getting Started

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/pay-gateway.git
    cd pay-gateway
    ```
2.  **Set up the environment variables:**
    *   Copy the `.env.example` file to a new file named `.env`.
    *   Update the `.env` file with your database credentials, JWT secret, Stripe API keys, and other configurations.
        ```
        DB_HOST=localhost
        DB_PORT=5432
        DB_NAME=paygateway
        DB_USERNAME=your_db_user
        DB_PASSWORD=your_db_password
        JWT_SECRET=your_jwt_secret
        STRIPE_SECRET_KEY=your_stripe_secret_key
        ```
3.  **Set up the database:**
    *   Make sure your PostgreSQL server is running.
    *   Create a new database with the name specified in your `.env` file (e.g., `paygateway`).
    *   The application uses Flyway to automatically manage database migrations. The necessary tables will be created when the application starts.
4.  **Build and run the application:**
    ```bash
    mvn spring-boot:run
    ```
    The application will be available at `http://localhost:8080`.

## API Endpoints

The API is documented using OpenAPI 3 (Swagger). Once the application is running, you can access the API documentation at `http://localhost:8080/swagger-ui.html`.

### Authentication

*   `POST /api/v1/auth/register`: Register a new user and merchant.
*   `POST /api/v1/auth/login`: Log in and receive a JWT.

### Payments

*   `POST /api/v1/payment-intents`: Create a new payment intent.
*   `GET /api/v1/payment-intents/{id}`: Retrieve a payment intent.
*   `POST /api/v1/payment-intents/{id}/confirm`: Confirm a payment intent.

For a full list of endpoints, please refer to the Swagger documentation.
