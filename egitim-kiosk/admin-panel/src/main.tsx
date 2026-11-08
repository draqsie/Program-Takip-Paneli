import { StrictMode, useEffect, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { GoogleAuthProvider, onIdTokenChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { ADMIN_EMAILS, auth, SCHOOL_ID } from "./firebase";
import { YonetimPaneli } from "./YonetimPaneli";

/**
 * Panel yalnızca yönetici e-postasıyla açılır. Buradaki kontrol sadece arayüz içindir; asıl koruma
 * Firestore kurallarındadır (backend/firestore.rules): listede olmayan biri paneli açsa bile
 * tek bir kayıt okuyamaz. Google hesabınızda iki adımlı doğrulamayı mutlaka açın.
 */
function App() {
  const [state, setState] = useState<
    { kind: "loading" } | { kind: "out" } | { kind: "denied"; user: User } | { kind: "admin"; user: User; schoolId: string }
  >({ kind: "loading" });

  useEffect(
    () =>
      onIdTokenChanged(auth, (user) => {
        if (!user) return setState({ kind: "out" });
        const email = (user.email ?? "").toLowerCase();
        if (user.emailVerified && ADMIN_EMAILS.includes(email)) {
          setState({ kind: "admin", user, schoolId: SCHOOL_ID });
        } else {
          setState({ kind: "denied", user });
        }
      }),
    [],
  );

  const shell = (children: ReactNode) => <div style={{ fontFamily: "system-ui", padding: 24, maxWidth: 1100, margin: "0 auto" }}>{children}</div>;

  switch (state.kind) {
    case "loading":
      return shell(<p>Yükleniyor…</p>);
    case "out":
      return shell(
        <>
          <h1>Okul Uygulama İzinleri</h1>
          <button onClick={() => signInWithPopup(auth, new GoogleAuthProvider())}>Google ile giriş</button>
        </>,
      );
    case "denied":
      return shell(
        <>
          <h1>Erişim yok</h1>
          <p>{state.user.email} hesabının yönetici yetkisi yok.</p>
          <button onClick={() => signOut(auth)}>Çıkış</button>
        </>,
      );
    case "admin":
      return shell(
        <>
          <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <h1 style={{ margin: 0 }}>Okul Uygulama İzinleri</h1>
            <span>
              {state.user.email} <button onClick={() => signOut(auth)}>Çıkış</button>
            </span>
          </header>
          <YonetimPaneli schoolId={state.schoolId} />
        </>,
      );
  }
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
