# BizNez go-live runbook

Target: **22 November 2026**. Follow this top to bottom. Each step says what you need in hand and how to check that it worked.

**Addresses used below** (change them in one place, `ops/production.env.example`, if you pick others):

| What | Address |
|---|---|
| App (businesses sign in here) | `https://suite.lezitech.site` |
| API (the app, Safaricom and PayHero call it) | `https://suite-api.lezitech.site` |
| Marketing website | `https://businez.com` |

---

## 0. What you need before starting

| Item | Where to get it | Blocks |
|---|---|---|
| A **new** Contabo VPS (Cloud VPS 4 or 6 is enough to start), Ubuntu 24.04 | contabo.com | everything |
| DNS access for `lezitech.site` and `businez.com` | your domain registrar / Cloudflare | HTTPS, email |
| Brevo account, sending domain verified | brevo.com → Senders, domains | password codes, invites, statements |
| Backblaze B2 bucket plus an application key (free to start; R2 later) | backblaze.com | photos, ETR uploads |
| PayHero account for LeziTech (API username, password, channel id) | app.payhero.co.ke | subscription payments |
| Off-server backup space (Backblaze B2 or a second R2 bucket) | backblaze.com or Cloudflare | backups surviving a dead server |

The platform does **not** go on the client-sites server (Trendysh, VunjaBei…). It gets its own VPS, added to the same Coolify as a remote server.

## 1. Server

1. In Coolify: **Servers → Add server**, enter the new VPS's IP and add Coolify's SSH key to it. Wait for "Validated".
2. On the VPS (as root):
   ```bash
   apt update && apt -y upgrade
   ufw allow 22 && ufw allow 80 && ufw allow 443 && ufw --force enable
   timedatectl set-timezone Africa/Nairobi
   ```
3. Check: Coolify shows the server green, and `docker ps` works on it.

## 2. DNS

At the DNS provider, add (proxy off / "DNS only" if Cloudflare, so Coolify's Let's Encrypt works):

| Type | Name | Value |
|---|---|---|
| A | `suite.lezitech.site` | VPS IP |
| A | `suite-api.lezitech.site` | VPS IP |

Email records come from Brevo (step 6). Check: `nslookup suite-api.lezitech.site` returns the VPS IP.

## 3. Database

1. Coolify → **New resource → Database → PostgreSQL 16** on the new server. Name it `suite-db`. Don't make it public.
2. Note the owner user and password Coolify shows; these are `FLYWAY_USERNAME` / `FLYWAY_PASSWORD`.
3. Create the database: open the Postgres terminal in Coolify and run `CREATE DATABASE suite;`
4. The app role `suite_app` is created by the first migration. **After the first API deploy (step 4)**, run `db/production-setup.sql` as the owner, with a long random password in place of the placeholder. That password is `DATABASE_PASSWORD`.

## 4. API

1. Coolify → **New resource → Application → GitHub** `MdauCodes/lezitech-suite-api`, branch `launch`, build pack **Dockerfile**, port **8090**.
2. Domain: `https://suite-api.lezitech.site`.
3. Environment variables: every line of `ops/production.env.example`, filled in. `DATABASE_URL` uses the Postgres service's internal hostname from Coolify.
4. Health check path: `/actuator/health`.
5. Deploy. The first start runs all migrations (V1 to the latest) as the owner.
   - It stops with "Refusing to start with unsafe settings" if a secret is missing or weak. The message names which one.
6. Run `db/production-setup.sql` (step 3.4), set `DATABASE_PASSWORD`, redeploy.
7. Check:
   ```bash
   curl -s https://suite-api.lezitech.site/actuator/health
   ```
   It should say `"status":"UP"`. Also check `curl -s https://suite-api.lezitech.site/api/public/plans` returns the plans.

## 5. Web app

1. Coolify → **New resource → Application → GitHub** `MdauCodes/lezitech-suite-web`, branch `launch`, build pack **Dockerfile**, port **80**.
2. Build argument (not a runtime variable): `VITE_API_URL=https://suite-api.lezitech.site`
3. Domain: `https://suite.lezitech.site`.
4. Deploy. Check: the page opens, the sign-in page loads, and the browser console shows no CORS errors. If it does, `CORS_ORIGINS` on the API is missing the app's address.

The marketing website (`website/`) is deployed separately to `businez.com`; its Start and Sign-in links point at `https://suite.lezitech.site`.

## 6. Email (Brevo)

1. Brevo → **Senders, domains & dedicated IPs → Domains → Add a domain** `businez.com`.
2. Add the records Brevo shows at the DNS provider: the **Brevo code TXT**, **DKIM** (CNAME or TXT), and **DMARC** (`_dmarc` TXT, start with `v=DMARC1; p=none; rua=mailto:<your inbox>`).
3. SPF: if `businez.com` already has an SPF TXT record, add `include:spf.brevo.com` to it. Never create a second SPF record.
4. Wait for Brevo to show all green, then create an **API key (v3)**. That is `EMAIL_API_KEY`; `EMAIL_FROM=no-reply@businez.com`.
5. Check: on the sign-in page, **Forgot password** with your own email. The code must arrive in the Gmail inbox (not spam) within a minute and look branded.

## 7. Payments

**LeziTech's own subscriptions (PayHero):**
1. In PayHero, the payment channel LeziTech collects on → its **channel id** is `PAYHERO_CHANNEL_ID`.
2. API username and password from PayHero → API keys.
3. Top up the PayHero service wallet (it pays the per-transaction fee). Switch **off** PayHero's SMS alerts to keep costs down.
4. Check: sign in as a test business, open **Plan and billing**, and pay for a plan from your own phone. The prompt arrives, you pay, and the plan shows as paid (a control-panel admin can refund or extend it afterwards). If the prompt arrives but the plan doesn't update, the callback can't reach the API: check `MPESA_CALLBACK_BASE`.

**Businesses' own M-Pesa:** nothing to set here. Each business connects its own PayHero (guided in the app) or asks LeziTech to set up Daraja. Callbacks go to `https://suite-api.lezitech.site/api/public/payhero/<token>` and `/api/public/mpesa/...`, which are already public.

## 8. Files (Backblaze B2 now, Cloudflare R2 later)

Start on B2 (first 10 GB free), move to R2 when it grows.
1. backblaze.com → **B2 Cloud Storage → Create a bucket** `biznez-files`, **Private**, in the EU region. Note the bucket's **Endpoint** (e.g. `s3.eu-central-003.backblazeb2.com`); the middle part is the region.
2. **Application Keys → Add a new key**, access to `biznez-files` only, Read and Write. Copy the keyID and applicationKey (shown once).
3. Set `FILES_STORE=b2` and the `B2_*` lines.
4. Check: in the app, add a product photo, redeploy the API, and confirm the photo is still there.

**Moving to R2 later:** create the R2 bucket and token, copy everything across with `rclone sync b2:biznez-files r2:biznez-files`, set `FILES_STORE=r2` and the `R2_*` lines, redeploy. File addresses in the app don't change.

## 9. Backups

1. On the VPS:
   ```bash
   cp ops/backup.sh /usr/local/bin/suite-backup && chmod 700 /usr/local/bin/suite-backup
   ```
2. Create `/etc/suite-backup.env` (chmod 600):
   ```
   PG_CONTAINER=<docker ps | grep postgres>
   PG_USER=<owner user>
   PG_DB=suite
   RCLONE_REMOTE=b2:biznez-backups
   ```
3. Install rclone (`curl https://rclone.org/install.sh | bash`) and `rclone config` the B2 or R2 remote.
4. Add the nightly job (`crontab -e`):
   ```
   15 2 * * * /usr/local/bin/suite-backup >> /var/log/suite-backup.log 2>&1
   ```
5. Run it once by hand and check the log says `backup ok` and `copied to`.

**Restore test (do it once before launch, then monthly).** A backup nobody has restored is a guess.
```bash
docker exec -i <pg-container> createdb -U <owner> suite_restore_test
docker exec -i <pg-container> pg_restore -U <owner> -d suite_restore_test --no-owner < /var/backups/suite/<latest>.dump
docker exec -i <pg-container> psql -U <owner> -d suite_restore_test -c "select count(*) from tenants;"
docker exec -i <pg-container> dropdb -U <owner> suite_restore_test
```
The count must match the live database.

## 10. Control panel settings

Sign in at `https://suite.lezitech.site` with `ADMIN_EMAIL` / `ADMIN_PASSWORD`, then:
1. Change the admin password.
2. **Platform settings:** free days (7, 14 or 21), plan prices and offers. These override `TRIAL_DAYS`.
3. Add other LeziTech admins with the role they need.

## 11. Launch-day checks (about 30 minutes)

Do these on a phone, as a new business, with **samples unticked**:

- [ ] Sign up a real test business; the welcome email arrives.
- [ ] First-day checklist: add a product with a photo, import 5 products from Excel (with and without photos).
- [ ] Add a staff member by email; they get the invite, set a password by code, sign in.
- [ ] Open the till, sell by cash, sell by M-Pesa prompt (KSh 10, real phone), close the till.
- [ ] Receipt on WhatsApp opens correctly.
- [ ] Credit sale to a customer, send their statement on WhatsApp; the link opens without signing in.
- [ ] Stock-take on one product; the difference shows in the activity log.
- [ ] Install the app from the browser prompt (Android Chrome).
- [ ] Control panel: the new business shows, with its free days counting down.
- [ ] Delete or mark the test business when done.

If any check fails: the API logs are in Coolify → API → Logs. Every request is logged with its time and status.

## 12. After launch

- Watch `/var/log/suite-backup.log` daily the first week.
- Coolify → API → Logs for `ERROR` lines the first few days.
- PayHero wallet balance weekly (about KSh 500 a month at low volume).
- Renew nothing by hand: Coolify renews HTTPS certificates itself.

## Rollback

Coolify keeps earlier deployments: API or web → **Deployments → Redeploy** an earlier one. Most migrations only add tables and columns, so an older API usually runs on a newer database; check the release's migrations before rolling back across one. If a migration itself breaks data, restore the night's backup (section 9) into the live database after stopping the API.
