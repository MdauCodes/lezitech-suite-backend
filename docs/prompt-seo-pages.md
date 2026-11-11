# Prompt for the SEO pages (for the second model)

Copy everything below the line into the other model's session.

---

You are building the SEO pages for **BizNez** (renamed from Lezi Suite on 2026-11-11), LeziTech's POS, CRM and ERP
app for Kenyan businesses. Go-live is **22 November 2026**. Another Claude session is working in the same
repositories at the same time on the restaurant QR ordering and till changes, so stay inside your area (below).

## Read first, in this order
1. `J:\backend\lezitech-suite-api\HANDOFF.md`
2. `J:\backend\lezitech-suite-api\docs\decisions.md`: the owner's answers. It wins over any plan.
3. `J:\backend\lezitech-suite-api\docs\SEO-AEO-GEO-PLAN.md`: the SEO plan (pages, technical checklist, JSON-LD, AEO,
   GEO, content plan, build order). Build to it.
4. `J:\frontend\lezitech-suite-web\website\README.md` and `website/src/` (the designed site: `components/lezi-site.tsx`,
   `lezi-pages.tsx`, `lezi-more.tsx`, `styles.css`, `lib/lezi-app.ts`, `routes/`).
5. Sector data: `J:\backend\lezitech-suite-api\src\main\resources\sectors.json` and `subsectors.json`
   (`SectorCatalog.pages()` lists the pages).

## The website
- TanStack Start, in `J:\frontend\lezitech-suite-web\website`. Run it with
  `npm --prefix J:/frontend/lezitech-suite-web/website run dev -- --port 5340 --strictPort`
  (preview config `suite-website`). The app runs on 5320 and the API on 8090.
- The design is final: the deep green palette (`--lezi-*` tokens in `styles.css`), rounded tiles, sections about
  90svh, elegant fades, compact on phones. New pages must use the same components and tokens. Do not restyle.
- The app no longer has marketing pages; its `/`, `/for/<sector>` and `/setup` redirect to the website
  (`src/lib/site.ts` in the app). Website links into the app go through `StartLink`/`SignInLink` in `lezi-site.tsx`,
  which carry the chosen business and the ad source (UTM, fbclid, gclid, ref). Keep that for every new link.

## What to build (from the SEO plan, sections 2, 3 and 10)
- Sector pages and **sub-sector pages** (`/sectors/{sector}/{sub}`) generated from the sector data, prerendered to
  static HTML. Start with the top 20 sub-sectors; the rest can follow in batches.
- **Town pages** (`/towns/{town}`) and chosen town-and-sector pairs, with real local content only.
- `/pricing` with the **new Lite price: KSh 2,650 a month, 3 staff** (Growth KSh 4,500, Pro KSh 9,000; yearly = 10 ×
  monthly). Change the price on the website only; the app's plan limits are changed by the other session.
- JSON-LD: Organization, SoftwareApplication with KES offers, FAQPage, BreadcrumbList. A sitemap, `robots.txt`,
  `llms.txt`, Open Graph images.
- The two calculators (Turnover Tax at **1.5%**, VAT) and the guide and comparison page templates; draft the first
  guides for the owner to review.
- About, contact, privacy and terms pages (privacy and terms marked as drafts for the owner's lawyer).

## Content rules (the owner's, non-negotiable)
- Plain warm English. Short sentences. No jargon.
- **Never claim a feature that isn't built.** Not built yet: buy by the carton and sell by the piece, customer
  statements. Show these as **"coming soon", with a note that we are building them now**. Pharmacy and medical clinic
  stay "coming soon". Beauty clinic is live. Check the app or API code when unsure whether something exists.
- A customer must never sense the platform is shared by many businesses: say "your business", never "each business",
  "tenant" or "workspace".
- The UI must not look AI-made: no sparkle icons, no pill-shaped eyebrow labels, no gradient blobs.
- Contacts: call +254 794 206 566, WhatsApp +254 796 814 154, email mdaucodes@gmail.com (the `CONTACT` object).
- M-Pesa: show both ways to connect, PayHero first. **Do not publish PayHero fee figures**; they are not verified.

## Ask the owner before building what depends on it
Section 9 of the SEO plan still has open questions: where the site is served (`lezitech.site/suite` or a root
domain), AI crawlers, the town list, how much content to review before launch, the search accounts, and named
competitors. Ask them, then **write the answers at the top of `docs/decisions.md`** (dated) before building on them.

## Stay in your area
- Edit only `J:\frontend\lezitech-suite-web\website\**` and SEO docs in the API repo. If you must change anything
  else (the app, the API, `sectors.json`), ask the owner first: the other session is editing the app and API.
- Never touch Moments Core or any other repository.

## Git
- Both repos use branch `launch`, with `main` kept equal: `git branch -f main launch && git push -q origin launch main`.
- Stage only your own files by path (`git add website/...`), never `git add -A` or `git add .`, because the other
  session has work in the same folders.
- Commit as the existing git identity (MdauCodes). **Never** add a `Co-Authored-By: Claude` trailer or a "Generated
  with Claude Code" line. Never force-push, amend or rebase anything already pushed.
- Small commits with plain-English messages saying what changed for the user.

## Done means
- Every new page renders on desktop and a 375px phone with no sideways scroll, in the existing design.
- The sitemap lists every page; structured data validates; the app stays `noindex`.
- `npx tsc --noEmit` in `website/` is clean.
- `HANDOFF.md` section 6b updated with what was built and what is left.
