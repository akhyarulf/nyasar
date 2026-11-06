# Nyasar email templates (English)

Complete, ready-to-paste bodies for **every** Supabase auth email and security
notice. English only — Supabase keeps a single template per event, so one
language is the only option unless you branch by `.Data`.

Each file is a self-contained `<table>` with inline CSS: Gmail strips `<style>`
blocks, and flexbox is not available in most email clients, so the layout is
table-based on purpose. Palette follows the app: sage `#5A7562`, forest
`#2E5339`, paper `#F4F1EA`.

## Authentication emails

| File | Dashboard → Authentication → Emails | Subject |
| --- | --- | --- |
| `01-confirm-signup.html` | Confirm sign up | `Confirm your Nyasar email` |
| `02-invite-user.html` | Invite user | `You're invited to Nyasar` |
| `03-magic-link-otp.html` | Magic link or OTP | `Your Nyasar sign-in code` |
| `04-change-email.html` | Change email address | `Confirm your new Nyasar email` |
| `05-reset-password.html` | Reset password | `Reset your Nyasar password` |
| `06-reauthentication.html` | Reauthentication | `Confirm it's you` |

## Security notifications

These only go out once the matching notification is switched on
(Authentication → Emails → Security). No links, no codes — they exist so a
change nobody made cannot happen quietly.

| File | Dashboard → Security notifications | Subject |
| --- | --- | --- |
| `07-password-changed.html` | Password changed | `Your Nyasar password was changed` |
| `08-email-changed.html` | Email address changed | `Your Nyasar email address was changed` |
| `09-phone-changed.html` | Phone number changed | `Your Nyasar phone number was changed` |
| `10-identity-linked.html` | Sign-in method linked | `A new sign-in method was added to Nyasar` |
| `11-identity-unlinked.html` | Sign-in method removed | `A sign-in method was removed from Nyasar` |
| `12-verification-added.html` | Verification method added | `Two-step verification was turned on for Nyasar` |
| `13-verification-removed.html` | Verification method removed | `Two-step verification was turned off for Nyasar` |

## Installing one

1. Open the template in the dashboard, switch the body editor to **Source**.
2. Delete everything, paste the whole file (comment at the top included — it is
   harmless and documents the template for the next person).
3. Set **Subject** from the table above.
4. **Save**, then repeat for the next one.

## Which template a sign-in actually uses

Not decided by the app — decided by the server:

- Address already registered → **Magic link or OTP** (`03`).
- Address never seen before → **Confirm sign up** (`01`), because *Confirm
  email* is on. Both files therefore carry the code block; skip one and half
  your users get an email with nothing to type.

## Variables used

| Variable | Contents |
| --- | --- |
| `{{ .Token }}` | the one-time code, digits only |
| `{{ .ConfirmationURL }}` | single-use verification link |
| `{{ .Email }}` | current address on the account |
| `{{ .NewEmail }}` | new address (`04`, `08`) |
| `{{ .OldEmail }}` | previous address (`08` only) |
| `{{ .Phone }}` / `{{ .OldPhone }}` | phone numbers (`09` only) |
| `{{ .Provider }}` | identity provider, e.g. `google` (`10`, `11`) |

If a template ever renders a code box empty, that event does not expose
`.Token` — delete the box and keep the button.

## Companion settings

- **URL Configuration → Redirect URLs**: add
  `https://app.nyasarnyaman.my.id/auth/callback` and
  `https://app.nyasarnyaman.my.id/auth/reset/`. The app asks for
  `/auth/callback` on sign-in emails, so a missing entry makes Supabase fall
  back to the Site URL and links never reach the app.
- **Emails → SMTP Settings**: a real provider (Resend, Brevo). The built-in
  sender only accepts project team addresses and is capped at a couple of
  messages per hour.
- **Providers → Email**: *Email OTP Length* = **6** (the app's code field takes
  six digits and enables itself at exactly six), *Email OTP Expiration* =
  **3600** seconds, matching the "expires in 1 hour" copy.

## Testing

1. Sign-in code, existing address: email shows a code **and** a button → type
   the code, you are in.
2. Request another code, tap the button from the phone instead → the app opens
   already signed in.
3. Repeat with a brand-new address → arrives as *Confirm sign up*, same result.
4. Reset password → lands on `/auth/reset/`, sets a new password, done.
5. Security notices: change a password with the notification switched on and
   confirm `07` arrives.

Codes and links are single-use; after one hour, or after using it, request a
fresh one.
