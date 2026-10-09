# 🚀 InfluenceHub

InfluenceHub is a full-stack platform that connects **brands and influencers** for collaboration, campaign management, and messaging.

---

## 🏗️ Tech Stack

### Backend
- Java (Spring Boot)
- Spring Security (JWT Authentication)
- JPA / Hibernate
- MySQL

### Frontend
- React (Vite)
- JavaScript

---

## 📂 Project Structure

```
influencehub/
├── backend/
└── frontend/
```

---

## ⚙️ Prerequisites

- Java 17+
- Maven
- Node.js (v16+)
- npm or yarn
- MySQL

---

## 🧩 Backend Setup

### 1. Navigate to backend
```
cd influencehub/backend
```

### 2. Configure Database & secrets

Non-secret settings have localhost defaults in `application.properties` (DB `jdbc:mysql://localhost:3306/influencehub`, user `root`, port `8082`) and can be overridden with environment variables (`DB_URL`, `DB_USER`, `PORT`, ...).

Secrets go in a git-ignored file:
```
cp secrets.properties.example secrets.properties
# then set spring.datasource.password and jwt.secret
```
Run the backend from the `backend/` folder so this file is found.

### 3. Run Backend
```
./mvnw spring-boot:run
```

Backend runs at:
http://localhost:8082

---

## 🎨 Frontend Setup

### 1. Navigate to frontend
```
cd influencehub/frontend
```

### 2. Install dependencies
```
npm install
```

### 3. Configure environment
`.env` already points to the local backend:
```
VITE_API_URL=http://localhost:8082
```

### 4. Run frontend
```
npm run dev
```

Frontend runs at:
http://localhost:5173

---

## 🔐 Authentication

- JWT-based authentication  
- Token expiration enabled  
- Passwords hashed using BCrypt  

---

## ✨ Features

- User Authentication  
- Campaign Management  
- Collaboration Requests  
- Messaging  
- Notifications  
- Influencer Discovery  

---

## 🏛️ Architecture

- N-Tier Monolithic Architecture  
- Controller → Service → Repository  
- REST API communication  

---

## 🧪 Non-Functional Requirement Tests

Security, data-consistency and performance tests: see [NFR_TESTING.md](NFR_TESTING.md).
```
cd backend && ./mvnw test
```

---

## ☁️ Deployment

GCP Cloud Run + Firebase Hosting + a free managed MySQL: see [DEPLOYMENT.md](DEPLOYMENT.md).

---

## 🚀 Future Improvements

- Microservices architecture  
- Real-time chat  
- Redis caching  
- Advanced analytics  

---

