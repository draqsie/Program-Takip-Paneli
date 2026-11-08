import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { GoogleAuthProvider, onIdTokenChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { auth } from "./firebase";
import { SinifKontrol } from "./SinifKontrol";

type Staff = { user: User; schoolId: string; role: "teacher" | "admin" };

function App() {
  const [staff, setStaff] = useState<Staff | null | undefined>(undefined);
  const [noRole, setNoRole] = useState(false);

  useEffect(
    () =>
      onIdTokenChanged(auth, async (user) => {
        if (!user) return setStaff(null);
        const { claims } = await user.getIdTokenResult();
        if ((claims.role === "teacher" || claims.role === "admin") && typeof claims.schoolId === "string") {
          setNoRole(false);
          setStaff({ user, schoolId: claims.schoolId, role: claims.role });
        } else {
          setNoRole(true);
          setStaff(null);
        }
      }),
    [],
  );

  if (staff === undefined) return <p style={{ padding: 24 }}>Yükleniyor…</p>;
  if (!staff)
    return (
      <div style={{ padding: 24, fontFamily: "system-ui" }}>
        <h1>Tablet Yönetimi</h1>
        {noRole && <p>Hesabınıza henüz öğretmen yetkisi verilmemiş. Okul yöneticisine başvurun.</p>}
        <button onClick={() => signInWithPopup(auth, new GoogleAuthProvider())}>Google ile giriş</button>
      </div>
    );

  return (
    <div style={{ fontFamily: "system-ui", padding: 24 }}>
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1 style={{ margin: 0 }}>Tablet Yönetimi</h1>
        <span>
          {staff.user.email} ({staff.role === "admin" ? "yönetici" : "öğretmen"}){" "}
          <button onClick={() => signOut(auth)}>Çıkış</button>
        </span>
      </header>
      <SinifKontrol schoolId={staff.schoolId} />
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
