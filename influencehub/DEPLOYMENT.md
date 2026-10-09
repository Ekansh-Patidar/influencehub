# Deploying InfluenceHub on GCP (free tier)

```
 Browser ──▶ Firebase Hosting (React build)          free Spark plan
    │
    │  HTTPS REST + JWT
    ▼
 Cloud Run: influencehub-api (Spring Boot container)  free monthly allowance, scales to zero
    │   DB_PASSWORD, JWT_SECRET  ◀── Secret Manager
    │  TLS (JDBC)
    ▼
 Free MySQL-compatible DB (TiDB Cloud Serverless or Aiven for MySQL)
```

Configuration follows the **12-factor** approach. The same jar runs everywhere, and everything that
differs between environments is an environment variable:

| Variable | Where it comes from | Example |
|---|---|---|
| `PORT` | set by Cloud Run automatically | `8080` |
| `DB_URL`, `DB_USER`, `DB_POOL_SIZE` | `backend/cloudrun.env.yaml` | `jdbc:mysql://host:4000/influencehub?sslMode=VERIFY_IDENTITY` |
| `CORS_ALLOWED_ORIGINS` | `backend/cloudrun.env.yaml` | `https://my-proj.web.app,https://my-proj.firebaseapp.com` |
| `DB_PASSWORD`, `JWT_SECRET` | **Secret Manager** | (never written in a file) |
| `VITE_API_URL` (frontend) | `frontend/.env.production`, baked in at **build time** | `https://influencehub-api-xxxx.run.app` |

Locally nothing changes. `backend/secrets.properties` (git-ignored) supplies the DB password and JWT
secret, and every other value has a localhost default.

> ⚠️ **Before you start:** the old MySQL password and JWT secret were committed in
> `application.properties` and are in git history. Don't reuse either in production. Use a new database
> password and generate a new JWT secret (step 4).

---

## 0. One-time prerequisites

1. A Google account and a **GCP project** with billing enabled (required even for free-tier usage).
2. **Set a budget alert immediately:** Console → Billing → Budgets & alerts → Create budget →
   amount e.g. ₹100 / $1 → alerts at 50%, 90%, 100%.
3. Use **Google Cloud Shell** (the `>_` icon in the Console) for the backend steps. It has `gcloud`,
   `git`, `openssl` and bash preinstalled, which avoids Windows quoting problems.
4. On your PC for the frontend: Node.js (already installed) and `npm install -g firebase-tools`.

Free-tier limits change, so check the current Cloud Run, Firebase and database pricing pages.

## 1. Create the free database

**Option 1: TiDB Cloud Serverless (MySQL-compatible)**
1. Sign up at tidbcloud.com and create a **Serverless** cluster. Pick the region closest to the Cloud
   Run region you'll use (e.g. AWS `us-east-1` with GCP `us-east1`), so queries don't cross continents.
2. Click **Connect**, choose "Java / JDBC", generate a password and note the host, port (`4000`) and user.
3. In its SQL editor run: `CREATE DATABASE influencehub;`
4. Your URL: `jdbc:mysql://<host>:4000/influencehub?sslMode=VERIFY_IDENTITY`

**Option 2: Aiven for MySQL (free plan)**
1. Create a free MySQL service at aiven.io and note the host, port, user (`avnadmin`) and password.
2. Your URL: `jdbc:mysql://<host>:<port>/defaultdb?sslMode=REQUIRED`
3. If the service has an IP allow-list, Cloud Run's outgoing IPs aren't fixed. Allow `0.0.0.0/0`, which
   is protected by TLS and a strong password (a fixed IP needs Cloud NAT, which isn't free).

Tables are created automatically on first start (`spring.jpa.hibernate.ddl-auto=update`).

## 2. Get the code into Cloud Shell and set variables

```bash
git clone https://github.com/<your-user>/<your-repo>.git
cd <your-repo>                  # the folder that contains backend/ and frontend/

export PROJECT_ID=<your-gcp-project-id>
export REGION=us-east1          # pick one close to your database
gcloud config set project $PROJECT_ID
gcloud config set run/region $REGION
```

## 3. Enable the APIs

```bash
gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
    artifactregistry.googleapis.com secretmanager.googleapis.com
```

## 4. Store the secrets in Secret Manager

```bash
# DB password from step 1 (printf, not echo: no trailing newline in the secret)
printf '%s' 'PASTE_DB_PASSWORD_HERE' | gcloud secrets create db-password --data-file=-

# Brand-new random JWT signing secret
openssl rand -base64 48 | tr -d '\n' | gcloud secrets create jwt-secret --data-file=-

# Let Cloud Run's runtime service account read them
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
for s in db-password jwt-secret; do
  gcloud secrets add-iam-policy-binding $s \
    --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

## 5. Fill in the non-secret config

```bash
cp backend/cloudrun.env.example.yaml backend/cloudrun.env.yaml   # git-ignored
nano backend/cloudrun.env.yaml
```
- Set `DB_URL` and `DB_USER` from step 1.
- Set `CORS_ALLOWED_ORIGINS` to `https://<PROJECT_ID>.web.app,https://<PROJECT_ID>.firebaseapp.com`.
  These are Firebase Hosting's default URLs. If the Firebase console later shows a different site name,
  update this value (see step 8).

## 5b. Let the build service account build from source

New projects don't automatically give the default compute service account broad permissions. Without
this step the first deploy fails with
`… does not have storage.objects.get access … run-sources-<project>-<region>`.

```bash
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/run.builder"
```
Wait 1–2 minutes for the permission to take effect. If a permission error persists, grant
`roles/storage.objectViewer`, `roles/artifactregistry.writer` and `roles/logging.logWriter` the same way.
This account now both builds and runs the service. That's fine for a personal project; production
setups use a separate build service account.

## 6. Run the tests, then deploy the backend to Cloud Run

Run the NFR test suite before every deploy. `./mvnw test` runs the 18 deterministic tests and
leaves out the machine-dependent latency test (PERF-2, tagged `load`), so it gives the same result
on a laptop, in Cloud Shell or in CI. The Docker build skips tests entirely.

```bash
# Pre-flight: no template placeholders left (must print nothing), password secret has the real length
grep -n "YOUR_" backend/cloudrun.env.yaml
gcloud secrets versions access latest --secret=db-password | wc -c   # 22 = placeholder still stored!

(cd backend && ./mvnw -B test)    # expect: Tests run: 18, Failures: 0 -> BUILD SUCCESS

gcloud run deploy influencehub-api \
  --source backend \
  --allow-unauthenticated \
  --memory 1Gi --cpu 1 --cpu-boost \
  --min-instances 0 --max-instances 2 \
  --env-vars-file backend/cloudrun.env.yaml \
  --set-secrets DB_PASSWORD=db-password:latest,JWT_SECRET=jwt-secret:latest
```
- `--source backend` builds `backend/Dockerfile` with Cloud Build. The first time, answer **Y** to
  creating an Artifact Registry repository.
- `--allow-unauthenticated` makes the URL reachable from browsers. The API still requires a JWT on
  every endpoint except login/register.
- `--min-instances 0` scales to zero, so it's free when idle. Expect a cold start of several seconds
  on the first request. `--cpu-boost` shortens it.
- `--max-instances 2` with `DB_POOL_SIZE=5` caps DB connections at 10 and caps cost.

Smoke test:
```bash
API_URL=$(gcloud run services describe influencehub-api --format='value(status.url)')
echo $API_URL
curl -i $API_URL/api/campaigns                         # expect 401 (auth enforced)
curl -s -X POST $API_URL/api/auth/login -H 'Content-Type: application/json' \
     -d '{"email":"nobody@x.com","password":"x"}'      # expect "Invalid email or password"
```

### 6b. Load-test the deployed API (PERF-2: p95 < 200 ms, 100 concurrent users)

This is where the report's latency target should be measured: against the real deployment, not a
laptop. First register one **brand** and one **influencer** account (through the UI after step 7, or
with `curl` against `/api/auth/register/...`), then in Cloud Shell:

```bash
login() {  # prints a JWT (valid 15 minutes)
  curl -s -X POST "$API_URL/api/auth/login" -H 'Content-Type: application/json' \
       -d "{\"email\":\"$1\",\"password\":\"$2\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])'
}
BRAND_TOKEN=$(login brand@example.com 'BrandPassword')
CREATOR_TOKEN=$(login creator@example.com 'CreatorPassword')

(cd backend && ./mvnw -B test -Pload-test -Dperf.baseUrl=$API_URL \
    -Dperf.brandToken=$BRAND_TOKEN -Dperf.influencerToken=$CREATOR_TOKEN)
```
- The test only **reads** (about 2,000 GET requests), so it creates no data and stays well within
  Cloud Run's free request allowance.
- Its 3 warm-up rounds absorb the cold start. Run it from Cloud Shell in the same region as the
  service so you measure the API, not your home internet.
- Scale the load with `-Dperf.users=50 -Dperf.requestsPerUser=40`. Seed a few campaigns and creators
  first so the detail endpoints are exercised too.
- The result (p50/p95/p99, throughput, errors) is the latency number you can quote, e.g. *"p95 = X ms
  at 100 concurrent users on Cloud Run"*.

## 7. Deploy the frontend to Firebase Hosting (on your PC)

```powershell
cd frontend
copy .env.production.example .env.production   # then set VITE_API_URL to the $API_URL from step 6
npm ci
npm run build                                   # VITE_API_URL is baked into dist/ here

firebase login
firebase projects:addfirebase <PROJECT_ID>      # only once: attach Firebase to the GCP project
firebase use --add <PROJECT_ID>                 # pick an alias, e.g. "prod"
firebase deploy --only hosting
```
Open `https://<PROJECT_ID>.web.app`, register a brand and an influencer, and try the full flow.

## 8. Changing config later

```bash
# e.g. after editing CORS_ALLOWED_ORIGINS in cloudrun.env.yaml
gcloud run services update influencehub-api --env-vars-file backend/cloudrun.env.yaml

# rotate a secret: add a new version; the next deployed revision picks up :latest
printf '%s' 'NEW_PASSWORD' | gcloud secrets versions add db-password --data-file=-
gcloud run services update influencehub-api --set-secrets DB_PASSWORD=db-password:latest
```
To redeploy code: re-run the `gcloud run deploy` command from step 6 for the backend, or
`npm run build && firebase deploy --only hosting` for the frontend.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Browser console: *blocked by CORS policy* | Frontend URL missing from `CORS_ALLOWED_ORIGINS`, or a typo (no trailing `/`). Update it (step 8) |
| Frontend calls `localhost:8082` | `.env.production` was missing at build time. Fix it, rebuild, redeploy |
| Deploy fails: *container failed to start / listen on PORT* | See the logs. Usually a bad `DB_URL`, DB unreachable, or a missing secret |
| `Could not resolve placeholder 'JWT_SECRET'` | `--set-secrets` missing, or no IAM binding (step 4) |
| `Communications link failure` / TLS errors | Wrong host/port, missing `sslMode=…`, or the DB IP allow-list blocks Cloud Run |
| `Too many connections` | Lower `DB_POOL_SIZE` or `--max-instances` |
| Logged out every 15 minutes | Expected: `JWT_EXPIRATION_MINUTES` defaults to 15 (NFR-SEC). Refresh tokens are the follow-up |

Logs: `gcloud run services logs read influencehub-api --limit 100`, or Console → Cloud Run → Logs.

## Tearing down

```bash
gcloud run services delete influencehub-api
gcloud secrets delete db-password && gcloud secrets delete jwt-secret
firebase hosting:disable
```
Also delete the images in Artifact Registry → `cloud-run-source-deploy` (storage beyond the free
allowance is billed).
