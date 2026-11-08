import { StrictMode, useEffect, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { GoogleAuthProvider, onIdTokenChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { auth } from "./firebase";
import { YonetimPaneli } from "./YonetimPaneli";

/**
 * Panel yalnızca role=admin claim'i taşıyan hesaba açılır. Bu claim'i yalnızca sunucu tarafında
 * (ilk-yonetici betiği) atayabilirsiniz; tarayıcıdan değiştirilemez. Asıl koruma Firestore
 * kurallarındadır: claim'i olmayan biri paneli açsa bile tek bir kayıt okuyamaz.
 * Hesabınızda iki adımlı doğrulamayı mutlaka açın.
 */
function App() {
  const [state, setState] = useState<
    { kind: "loading" } | { kind: "out" } | { kind: "denied"; user: User } | { kind: "admin"; user: User; schoolId: string }
  >({ kind: "loading" });

  useEffect(
    () =>
      onIdTokenChanged(auth, async (user) => {
        if (!user) return setState({ kind: "out" });
        const { claims } = await user.getIdTokenResult();
        if (claims.role === "admin" && typeof claims.schoolId === "string") {
          setState({ kind: "admin", user, schoolId: claims.schoolId });
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
