import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFirestore } from "firebase/firestore";

// .env.local'den okunur (bkz. .env.example). Bu değerler gizli değildir; güvenliği
// backend/firestore.rules sağlar.
const app = initializeApp({
  apiKey: import.meta.env.VITE_FB_API_KEY,
  authDomain: import.meta.env.VITE_FB_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FB_PROJECT_ID,
  appId: import.meta.env.VITE_FB_APP_ID,
});

export const auth = getAuth(app);
export const db = getFirestore(app);
export const SCHOOL_ID: string = import.meta.env.VITE_SCHOOL_ID ?? "";
export const ADMIN_EMAILS: string[] = String(import.meta.env.VITE_ADMIN_EMAILS ?? "")
  .split(",").map((s: string) => s.trim().toLowerCase()).filter(Boolean);
