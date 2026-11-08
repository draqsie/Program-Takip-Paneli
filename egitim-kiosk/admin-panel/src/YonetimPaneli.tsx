import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from "react";
import {
  arrayRemove, arrayUnion, collection, deleteField, doc, FieldPath, limit, onSnapshot, orderBy,
  query, serverTimestamp, setDoc, Timestamp, updateDoc, where, writeBatch,
} from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { db, functions } from "./firebase";

// ----------------------------------------------------------------------------- veri tipleri

type Platform = "android" | "ios";
type Duration = "LESSON" | "TODAY" | "PERMANENT";

type Schedule = { days: number[]; start: string; end: string }; // days: 1=Pzt … 7=Paz
type Settings = { schedule: Schedule; paused: boolean; defaultAllowedAndroid: string[] };

type Student = {
  id: string;
  name: string;
  className: string;
  platform: Platform;
  allowed: string[];                         // kalıcı izinli appKey'ler
  grants: Record<string, Timestamp>;         // geçici izinler: appKey → bitiş
  labels: Record<string, string>;            // appKey → görünen ad (iOS için onaylarken kaydedilir)
  inventory: { id: string; label: string }[]; // yalnızca Android: cihazdaki uygulamalar
  status?: {
    lastSeen?: Timestamp;
    protection?: "ACTIVE" | "PERMISSION_MISSING" | "DISABLED";
    missing?: string[];
    clockSkewSec?: number;
    appVersion?: string;
  };
};

type Request = {
  id: string;
  studentId: string;
  studentName: string;
  className: string;
  platform: Platform;
  appKey: string;
  appLabel: string;
  labelVerified: boolean;
  note?: string;
  status: "PENDING" | "APPROVED" | "DENIED";
  duration?: Duration;
  createdAt?: Timestamp;
  decidedAt?: Timestamp;
  expiresAt?: Timestamp | null;
};

const DEFAULT_SETTINGS: Settings = {
  schedule: { days: [1, 2, 3, 4, 5], start: "08:30", end: "15:30" },
  paused: false,
  defaultAllowedAndroid: [],
};
const DAY_NAMES = ["Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"];
const LESSON_MINUTES = 40;

// ----------------------------------------------------------------------------- yardımcılar

function useQuery<T>(q: ReturnType<typeof query> | null): T[] {
  const [rows, setRows] = useState<T[]>([]);
  useEffect(() => {
    if (!q) return;
    return onSnapshot(q, (snap) => setRows(snap.docs.map((d) => ({ id: d.id, ...(d.data() as object) }) as T)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);
  return rows;
}

function fmtAgo(t?: Timestamp): string {
  if (!t) return "hiç";
  const min = Math.round((Date.now() - t.toMillis()) / 60_000);
  if (min < 1) return "az önce";
  if (min < 60) return `${min} dk önce`;
  if (min < 24 * 60) return `${Math.round(min / 60)} sa önce`;
  return t.toDate().toLocaleDateString("tr-TR");
}

function expiryFor(duration: Duration, settings: Settings): Timestamp | null {
  if (duration === "PERMANENT") return null;
  if (duration === "LESSON") return Timestamp.fromMillis(Date.now() + LESSON_MINUTES * 60_000);
  const [h, m] = settings.schedule.end.split(":").map(Number);
  const end = new Date();
  end.setHours(h, m, 0, 0);
  return Timestamp.fromDate(end.getTime() > Date.now() ? end : new Date(Date.now() + 60 * 60_000));
}

/** Kırmızı/sarı/yeşil koruma durumu. Android 5 dk'da bir rapor verir; iOS yalnızca uygulama açıldığında. */
function protectionBadge(s: Student): { text: string; color: string } {
  const st = s.status ?? {};
  if (st.protection === "DISABLED") return { text: "Koruma kapatılmış", color: "#c62828" };
  if (st.protection === "PERMISSION_MISSING") return { text: `İzin eksik: ${(st.missing ?? []).join(", ")}`, color: "#c62828" };
  if (Math.abs(st.clockSkewSec ?? 0) > 300) return { text: "Cihaz saati oynanmış", color: "#ef6c00" };
  const staleMin = s.platform === "android" ? 20 : 24 * 60;
  if (!st.lastSeen || Date.now() - st.lastSeen.toMillis() > staleMin * 60_000) return { text: "Rapor gelmiyor", color: "#ef6c00" };
  return { text: "Aktif", color: "#2e7d32" };
}

// ----------------------------------------------------------------------------- ana bileşen

export function YonetimPaneli({ schoolId }: { schoolId: string }) {
  const base = `schools/${schoolId}`;
  const [tab, setTab] = useState<"istekler" | "ogrenciler" | "ayarlar">("istekler");
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS);

  useEffect(
    () => onSnapshot(doc(db, `${base}/config/settings`), (s) => setSettings({ ...DEFAULT_SETTINGS, ...(s.data() as Partial<Settings>) })),
    [base],
  );

  const pendingQ = useMemo(
    () => query(collection(db, `${base}/requests`), where("status", "==", "PENDING"), orderBy("createdAt", "asc")),
    [base],
  );
  const pending = useQuery<Request>(pendingQ);
  const studentsQ = useMemo(() => query(collection(db, `${base}/students`), orderBy("className"), orderBy("name")), [base]);
  const students = useQuery<Student>(studentsQ);

  // Panel açıkken yeni istek gelirse tarayıcı bildirimi göster.
  const seen = useRef<Set<string> | null>(null);
  useEffect(() => {
    const ids = new Set(pending.map((r) => r.id));
    if (seen.current && "Notification" in window && Notification.permission === "granted") {
      pending.filter((r) => !seen.current!.has(r.id)).forEach((r) =>
        new Notification("Yeni izin isteği", { body: `${r.studentName} → ${r.appLabel}` }));
    }
    seen.current = ids;
  }, [pending]);

  return (
    <>
      <nav style={{ display: "flex", gap: 8, margin: "16px 0" }}>
        <TabButton active={tab === "istekler"} onClick={() => setTab("istekler")}>İstekler {pending.length > 0 && `(${pending.length})`}</TabButton>
        <TabButton active={tab === "ogrenciler"} onClick={() => setTab("ogrenciler")}>Öğrenciler ({students.length})</TabButton>
        <TabButton active={tab === "ayarlar"} onClick={() => setTab("ayarlar")}>Ayarlar</TabButton>
        {"Notification" in window && Notification.permission === "default" && (
          <button onClick={() => Notification.requestPermission()}>Masaüstü bildirimlerini aç</button>
        )}
      </nav>
      {settings.paused && <p style={warn}>Kısıtlamalar şu anda DURAKLATILMIŞ (Ayarlar sekmesinden açın).</p>}
      {tab === "istekler" && <Istekler base={base} pending={pending} settings={settings} />}
      {tab === "ogrenciler" && <Ogrenciler base={base} students={students} settings={settings} />}
      {tab === "ayarlar" && <Ayarlar base={base} settings={settings} />}
    </>
  );
}

// ----------------------------------------------------------------------------- istekler

function Istekler({ base, pending, settings }: { base: string; pending: Request[]; settings: Settings }) {
  const recentQ = useMemo(
    () => query(collection(db, `${base}/requests`), where("status", "in", ["APPROVED", "DENIED"]), orderBy("decidedAt", "desc"), limit(20)),
    [base],
  );
  const recent = useQuery<Request>(recentQ);

  /** Onay: istek kaydı + öğrencinin izin listesi tek atomik yazmada güncellenir. */
  const approve = async (r: Request, duration: Duration) => {
    const expiresAt = expiryFor(duration, settings);
    const studentRef = doc(db, `${base}/students/${r.studentId}`);
    const batch = writeBatch(db);
    batch.update(doc(db, `${base}/requests/${r.id}`), {
      status: "APPROVED", duration, expiresAt, decidedAt: serverTimestamp(),
    });
    // Paket adlarında nokta olduğundan ("com.ornek.app") alan yolu FieldPath ile verilir.
    if (duration === "PERMANENT") {
      batch.update(studentRef, "allowed", arrayUnion(r.appKey), new FieldPath("labels", r.appKey), r.appLabel);
    } else {
      batch.update(studentRef, new FieldPath("grants", r.appKey), expiresAt, new FieldPath("labels", r.appKey), r.appLabel);
    }
    await batch.commit();
  };

  const deny = (r: Request) =>
    updateDoc(doc(db, `${base}/requests/${r.id}`), { status: "DENIED", decidedAt: serverTimestamp() });

  return (
    <>
      <h2>Bekleyen istekler</h2>
      {pending.length === 0 && <p style={{ color: "#666" }}>Bekleyen istek yok.</p>}
      {pending.map((r) => (
        <div key={r.id} style={card}>
          <div>
            <strong>{r.studentName}</strong> <span style={{ color: "#666" }}>({r.className}, {r.platform === "ios" ? "iPad/iPhone" : "Android"})</span>
            <div style={{ fontSize: 20, margin: "6px 0" }}>
              {r.appLabel}{" "}
              {!r.labelVerified && <span style={pill} title="iOS uygulama adını gizler; bu ad öğrencinin kendi yazdığıdır.">öğrenci beyanı</span>}
            </div>
            {r.platform === "android" && <code style={{ color: "#666" }}>{r.appKey}</code>}
            {r.note && <div style={{ marginTop: 6 }}>“{r.note}”</div>}
            <div style={{ color: "#888", fontSize: 13 }}>{fmtAgo(r.createdAt)}</div>
          </div>
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap", alignItems: "start" }}>
            <button onClick={() => approve(r, "LESSON")}>{LESSON_MINUTES} dk izin</button>
            <button onClick={() => approve(r, "TODAY")}>Bugün için</button>
            <button onClick={() => approve(r, "PERMANENT")}>Kalıcı izin</button>
            <button onClick={() => deny(r)} style={{ color: "#c62828" }}>Reddet</button>
          </div>
        </div>
      ))}

      <h2>Son kararlar</h2>
      <table style={table}>
        <tbody>
          {recent.map((r) => (
            <tr key={r.id}>
              <td style={td}>{r.status === "APPROVED" ? "✓" : "✗"}</td>
              <td style={td}>{r.studentName}</td>
              <td style={td}>{r.appLabel}</td>
              <td style={td}>{r.status === "APPROVED" ? ({ LESSON: `${LESSON_MINUTES} dk`, TODAY: "bugün", PERMANENT: "kalıcı" }[r.duration ?? "PERMANENT"]) : "reddedildi"}</td>
              <td style={td}>{fmtAgo(r.decidedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

// ----------------------------------------------------------------------------- öğrenciler

function Ogrenciler({ base, students, settings }: { base: string; students: Student[]; settings: Settings }) {
  const [selected, setSelected] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const shown = students.filter((s) => `${s.name} ${s.className}`.toLocaleLowerCase("tr").includes(filter.toLocaleLowerCase("tr")));
  const current = students.find((s) => s.id === selected);

  return (
    <div style={{ display: "grid", gridTemplateColumns: current ? "1fr 1fr" : "1fr", gap: 24 }}>
      <div>
        <input placeholder="Ara: ad veya sınıf" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ width: "100%", padding: 8 }} />
        <table style={table}>
          <thead>
            <tr><th style={th}>Öğrenci</th><th style={th}>Sınıf</th><th style={th}>Cihaz</th><th style={th}>Koruma</th><th style={th}>Son rapor</th></tr>
          </thead>
          <tbody>
            {shown.map((s) => {
              const b = protectionBadge(s);
              return (
                <tr key={s.id} onClick={() => setSelected(s.id)} style={{ cursor: "pointer", background: s.id === selected ? "#eef3ff" : undefined }}>
                  <td style={td}>{s.name}</td>
                  <td style={td}>{s.className}</td>
                  <td style={td}>{s.platform === "ios" ? "iOS" : "Android"}</td>
                  <td style={{ ...td, color: b.color, fontWeight: 600 }}>{b.text}</td>
                  <td style={td}>{fmtAgo(s.status?.lastSeen)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {current && <OgrenciDetay base={base} s={current} settings={settings} onClose={() => setSelected(null)} />}
    </div>
  );
}

function OgrenciDetay({ base, s, settings, onClose }: { base: string; s: Student; settings: Settings; onClose: () => void }) {
  const ref = doc(db, `${base}/students/${s.id}`);
  const setAllowed = (key: string, on: boolean) => updateDoc(ref, { allowed: on ? arrayUnion(key) : arrayRemove(key) });
  const revokeGrant = (key: string) => updateDoc(ref, new FieldPath("grants", key), deleteField());
  const activeGrants = Object.entries(s.grants ?? {}).filter(([, t]) => t.toMillis() > Date.now());
  const isDefault = (key: string) => settings.defaultAllowedAndroid.includes(key);

  return (
    <div style={card}>
      <div style={{ width: "100%" }}>
        <div style={{ display: "flex", justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>{s.name}</h2>
          <button onClick={onClose}>Kapat</button>
        </div>
        <p style={{ color: "#666" }}>{s.className} · {s.platform === "ios" ? "iPad/iPhone" : "Android"} · uygulama {s.status?.appVersion ?? "?"}</p>

        {activeGrants.length > 0 && (
          <>
            <h3>Geçici izinler</h3>
            {activeGrants.map(([key, t]) => (
              <div key={key}>
                {s.labels?.[key] ?? key} — {t.toDate().toLocaleTimeString("tr-TR", { hour: "2-digit", minute: "2-digit" })}'e kadar{" "}
                <button onClick={() => revokeGrant(key)}>Geri al</button>
              </div>
            ))}
          </>
        )}

        {s.platform === "android" ? (
          <>
            <h3>Cihazdaki uygulamalar — okul saatinde izinli olanları işaretleyin</h3>
            {s.inventory.length === 0 && <p style={{ color: "#666" }}>Cihaz henüz uygulama listesini göndermedi.</p>}
            {[...s.inventory].sort((a, b) => a.label.localeCompare(b.label, "tr")).map((app) => (
              <label key={app.id} style={{ display: "block", padding: "3px 0" }} title={app.id}>
                <input
                  type="checkbox"
                  checked={isDefault(app.id) || s.allowed.includes(app.id)}
                  disabled={isDefault(app.id)}
                  onChange={(e) => setAllowed(app.id, e.target.checked)}
                />{" "}
                {app.label} {isDefault(app.id) && <span style={pill}>herkese açık</span>}
              </label>
            ))}
          </>
        ) : (
          <>
            <h3>Kalıcı izinli uygulamalar</h3>
            <p style={{ color: "#666", fontSize: 14 }}>
              Apple, iPad/iPhone'daki uygulama listesini üçüncü taraf uygulamalarla paylaşmaz. Bu yüzden iOS'ta izinler
              yalnızca öğrencinin isteğiyle eklenir; adlar öğrencinin beyanıdır.
            </p>
            {s.allowed.length === 0 && <p>Henüz yok.</p>}
            {s.allowed.map((key) => (
              <div key={key}>
                {s.labels?.[key] ?? "(adsız)"} <button onClick={() => setAllowed(key, false)}>Kaldır</button>
              </div>
            ))}
          </>
        )}
      </div>
    </div>
  );
}

// ----------------------------------------------------------------------------- ayarlar

function Ayarlar({ base, settings }: { base: string; settings: Settings }) {
  const [draft, setDraft] = useState(settings);
  const [defaults, setDefaults] = useState(settings.defaultAllowedAndroid.join("\n"));
  const [codeClass, setCodeClass] = useState("");
  const [code, setCode] = useState<string | null>(null);
  useEffect(() => { setDraft(settings); setDefaults(settings.defaultAllowedAndroid.join("\n")); }, [settings]);

  const save = () =>
    setDoc(doc(db, `${base}/config/settings`), {
      ...draft,
      defaultAllowedAndroid: defaults.split(/\s+/).map((x) => x.trim()).filter(Boolean),
      updatedAt: serverTimestamp(),
    }, { merge: true });

  const togglePause = () => setDoc(doc(db, `${base}/config/settings`), { paused: !settings.paused }, { merge: true });

  const makeCode = async () => {
    const res = await httpsCallable<{ className: string }, { code: string }>(functions, "createEnrollmentCode")({ className: codeClass });
    setCode(res.data.code);
  };

  const toggleDay = (d: number) =>
    setDraft({ ...draft, schedule: { ...draft.schedule, days: draft.schedule.days.includes(d) ? draft.schedule.days.filter((x) => x !== d) : [...draft.schedule.days, d].sort() } });

  return (
    <>
      <h2>Okul saatleri</h2>
      <p>Kısıtlamalar yalnızca bu saatlerde uygulanır; dışında öğrencinin tableti tamamen serbesttir.</p>
      <div>
        {DAY_NAMES.map((n, i) => (
          <label key={n} style={{ marginRight: 12 }}>
            <input type="checkbox" checked={draft.schedule.days.includes(i + 1)} onChange={() => toggleDay(i + 1)} /> {n}
          </label>
        ))}
      </div>
      <p>
        Başlangıç <input type="time" value={draft.schedule.start} onChange={(e) => setDraft({ ...draft, schedule: { ...draft.schedule, start: e.target.value } })} />{" "}
        Bitiş <input type="time" value={draft.schedule.end} onChange={(e) => setDraft({ ...draft, schedule: { ...draft.schedule, end: e.target.value } })} />
      </p>

      <h2>Herkese açık Android uygulamaları</h2>
      <p>Her satıra bir paket adı (ör. <code>org.geogebra.android</code>). Bu uygulamalar tüm Android öğrencilerde okul saatinde açıktır.</p>
      <textarea rows={6} style={{ width: "100%", fontFamily: "monospace" }} value={defaults} onChange={(e) => setDefaults(e.target.value)} />
      <p><button onClick={save}>Kaydet</button></p>

      <h2>Duraklat</h2>
      <p>Tatil, gezi vb. günlerde tüm kısıtlamaları tek tıkla kaldırın.</p>
      <button onClick={togglePause}>{settings.paused ? "Kısıtlamaları yeniden başlat" : "Tüm kısıtlamaları duraklat"}</button>

      <h2>Öğrenci kayıt kodu</h2>
      <p>Öğrenci uygulamayı indirdikten sonra bu kodu girer. Kod 7 gün ve 40 kayıt için geçerlidir.</p>
      <input placeholder="Sınıf (ör. 9-A)" value={codeClass} onChange={(e) => setCodeClass(e.target.value)} />{" "}
      <button disabled={!codeClass.trim()} onClick={makeCode}>Kod üret</button>
      {code && <p style={{ fontSize: 28, letterSpacing: 4, fontFamily: "monospace" }}>{code}</p>}
    </>
  );
}

// ----------------------------------------------------------------------------- stil

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button onClick={onClick} style={{ padding: "8px 14px", fontWeight: active ? 700 : 400, borderBottom: active ? "3px solid #1a47b8" : "3px solid transparent" }}>
      {children}
    </button>
  );
}

const card: CSSProperties = { display: "flex", justifyContent: "space-between", gap: 16, border: "1px solid #ddd", borderRadius: 10, padding: 14, margin: "10px 0" };
const pill: CSSProperties = { fontSize: 12, background: "#fff3e0", color: "#e65100", padding: "2px 8px", borderRadius: 10 };
const warn: CSSProperties = { background: "#fff3e0", padding: 10, borderRadius: 8 };
const table: CSSProperties = { borderCollapse: "collapse", width: "100%", marginTop: 8 };
const th: CSSProperties = { borderBottom: "2px solid #ccc", padding: "6px 8px", textAlign: "left" };
const td: CSSProperties = { borderBottom: "1px solid #eee", padding: "6px 8px" };
