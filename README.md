#  E-Commerce Microservices Platform

A cloud-native e-commerce backend built with Spring Boot microservices architecture, featuring JWT authentication, PostgreSQL databases, and Docker containerization.

## Quick Start (Verified)

The entire stack runs via the root `docker-compose.yml`. No per-service Maven runs needed.

### Prerequisites
- Docker Desktop running (tested with engine v28.5.1, Compose v2.40.3)
- Host ports free: **5432, 5433, 5434, 5435** (Postgres × 4), **6379** (Redis), **8080–8084** (services). If any of these are bound by other containers, stop them first.
- ~2 GB free disk for images; first build downloads Maven dependencies for 5 services (~5 min cold).

### Run
```bash
git clone https://github.com/vsvidhun06-blip/ecommerce-microservices.git
cd ecommerce-microservices
docker compose up --build
```

### What you should see
After the build completes, 10 containers come up: 4 Postgres DBs, Redis, and 5 Spring Boot services. Wait ~30 s for the services to finish booting, then:

| Port | Service | Health endpoint | Expected |
|---:|---|---|---|
| 8080 | api-gateway | `GET /actuator/health` | **200** `{"status":"UP"}` |
| 8081 | product-service | `GET /actuator/health` | **200** `{"status":"UP"}` |
| 8082 | user-service | `GET /actuator/health` | **200** `{"status":"UP"}` |
| 8083 | order-service | `GET /actuator/health` | **200** `{"status":"UP"}` |
| 8084 | notification-service | `GET /actuator/health` | **200** `{"status":"UP"}` |

Verify all five:
```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8084/actuator/health
```

### Known caveats (not blocking startup)
- **No Kafka / no Eureka** in `docker-compose.yml`. The `notification-service` source has a `@KafkaListener` and points at `localhost:9092`; it logs repeated `Bootstrap broker ... disconnected` warnings but the container stays healthy. The `infrastructure/docker-compose.yml` is a separate scaffold (not used by the root compose) and includes Kafka+Zookeeper if you want to wire it up.
- **api-gateway routes** in `application.properties` point at `localhost:<port>`. Inside the Docker network, `localhost` is the container itself, so proxied requests through the gateway won't reach the backend services. Hit each service directly on its host port instead.
- The `infrastructure/` directory contains an older Compose scaffold. The root `docker-compose.yml` is the one that runs the project.

### Stopping
```bash
docker compose down            # stops and removes containers, keeps volumes
docker compose down -v         # also removes the Postgres volumes
```

##  Architecture
```
CLIENT
  |
API Gateway (Port 8080)
  |
  |-- /api/v1/products/** --> Product Service (Port 8081)
  |-- /api/v1/auth/**     --> User Service    (Port 8082)
  |-- /api/v1/users/**    --> User Service    (Port 8082)
  |-- /api/v1/orders/**   --> Order Service   (Port 8083)
  |-- /api/v1/notify/**   --> Notification    (Port 8084)
```

## 🚀 Microservices Overview

| Service | Port | Description | Status |
|---------|------|-------------|--------|
| API Gateway | 8080 | Single entry point, routes all requests | ✅ Complete |
| Product Service | 8081 | Full product CRUD operations | ✅ Complete |
| User Service | 8082 | JWT Authentication & user management | ✅ Complete |
| Order Service | 8083 | Order creation & management | ✅ Complete |
| Notification Service | 8084 | Email notifications | ✅ Complete |

## 🛠️ Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.0 |
| Security | Spring Security + JWT |
| Database | PostgreSQL 15 |
| Containers | Docker |
| Gateway | Spring Cloud Gateway |
| Build Tool | Maven |
| API Docs | SpringDoc OpenAPI |

## 📋 Prerequisites

- Java 17+
- Docker Desktop
- Maven 3.8+
- PostgreSQL (via Docker)

## 🚀 Getting Started (legacy manual setup)

> Prefer the **Quick Start** section at the top of this README — it runs everything via `docker compose up`. The steps below are the legacy per-service manual workflow.

### 1. Clone the Repository
```bash
git clone https://github.com/vsvidhun06-blip/ecommerce-microservices.git
cd ecommerce-microservices
```

### 2. Start Databases
```bash
# Product Database
docker run -d --name product-db \
  -e POSTGRES_DB=product_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:15

# User Database
docker run -d --name user-db \
  -e POSTGRES_DB=user_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 postgres:15

# Order Database
docker run -d --name order-db \
  -e POSTGRES_DB=order_db \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5434:5432 postgres:15
```

### 3. Run Services
```bash
# Terminal 1
cd product-service && mvn spring-boot:run

# Terminal 2
cd user-service && mvn spring-boot:run

# Terminal 3
cd order-service && mvn spring-boot:run

# Terminal 4
cd api-gateway && mvn spring-boot:run
```

## 📡 API Endpoints

### Product Service
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | /api/v1/products | Get all products |
| GET | /api/v1/products/{id} | Get product by ID |
| POST | /api/v1/products | Create product |
| PUT | /api/v1/products/{id} | Update product |
| DELETE | /api/v1/products/{id} | Delete product |

### User Service
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/auth/register | Register new user |
| POST | /api/v1/auth/login | Login and get JWT token |
| GET | /api/v1/users/{id} | Get user by ID |
| PUT | /api/v1/users/{id} | Update user |
| DELETE | /api/v1/users/{id} | Delete user |

### Order Service
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/orders | Create new order |
| GET | /api/v1/orders/{id} | Get order by ID |
| GET | /api/v1/orders/user/{userId} | Get all orders by user |
| PUT | /api/v1/orders/{id}/status | Update order status |
| DELETE | /api/v1/orders/{id} | Delete order |

## 🔐 Security Features

- JWT token-based authentication
- BCrypt password hashing
- Role-based access control (USER / ADMIN)
- Stateless session management
- Protected endpoints via Spring Security

## 🗄️ Database Schema

### Products
```sql
id, name, description, price, stock_quantity, created_at, updated_at
```

### Users
```sql
id, email, password, first_name, last_name, role, is_active, created_at, updated_at
```

### Orders
```sql
id, user_id, total_amount, status, created_at, updated_at
```

### Order Items
```sql
id, order_id, product_id, quantity, price
```

## 📁 Project Structure
```
ecommerce-microservices/
├── api-gateway/
├── product-service/
├── user-service/
├── order-service/
└── notification-service/
```

## 👨‍💻 Author

**Vidhun V S**
- GitHub: [@vsvidhun06-blip](https://github.com/vsvidhun06-blip)
- LinkedIn: [Vidhun V S](https://www.linkedin.com/in/vidhun-v-s)

## 📄 License

This project is open source and available under the [MIT License](LICENSE).
