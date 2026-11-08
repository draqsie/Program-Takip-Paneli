import { useEffect, useMemo, useState, type CSSProperties } from "react";
import {
  addDoc, arrayRemove, arrayUnion, collection, doc, increment, onSnapshot,
  serverTimestamp, updateDoc, type Timestamp,
} from "firebase/firestore";
import { db } from "./firebase";

type Mode = "NORMAL" | "SINAV" | "KILITLI";
type Group = { id: string; name: string; mode: Mode; allowedApps: string[]; policyVersion?: number };
type CatalogApp = { id: string; label: string };
type Device = {
  id: string;
  label: string;
  groupId: string | null;
  status?: {
    lastSeen?: Timestamp;
    battery?: number;
    mode?: Mode;
    appliedPolicyVersion?: number;
    foreground?: string | null;
  };
};
type CommandType = "SYNC" | "MESSAGE" | "OPEN_BOOK" | "MAINTENANCE" | "REBOOT";

const MODE_LABEL: Record<Mode, string> = { NORMAL: "Normal", SINAV: "Sınav (sadece kitap)", KILITLI: "Ekranları kilitle" };
const ONLINE_MS = 10 * 60_000;

/** Firestore koleksiyonuna canlı abone olur; panel ve tabletler aynı veriyi anlık görür. */
function useCollection<T>(path: string): T[] {
  const [rows, setRows] = useState<T[]>([]);
  useEffect(
    () => onSnapshot(collection(db, path), (snap) => setRows(snap.docs.map((d) => ({ id: d.id, ...d.data() }) as T))),
    [path],
  );
  return rows;
}

export function SinifKontrol({ schoolId }: { schoolId: string }) {
  const base = `schools/${schoolId}`;
  const groups = useCollection<Group>(`${base}/groups`);
  const apps = useCollection<CatalogApp>(`${base}/apps`);
  const devices = useCollection<Device>(`${base}/devices`);
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);

  // "Şimdi" her 30 sn'de bir güncellenir ki çevrimiçi göstergesi canlı kalsın.
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);

  const groupById = useMemo(() => new Map(groups.map((g) => [g.id, g])), [groups]);

  // ---- yazma işlemleri: her değişiklik policyVersion'ı artırır; cihaz uyguladığında bu sürümü geri raporlar.
  const toggleApp = (g: Group, pkg: string, allowed: boolean) =>
    updateDoc(doc(db, `${base}/groups/${g.id}`), {
      allowedApps: allowed ? arrayUnion(pkg) : arrayRemove(pkg),
      policyVersion: increment(1),
    });

  const setMode = (g: Group, mode: Mode) =>
    updateDoc(doc(db, `${base}/groups/${g.id}`), { mode, policyVersion: increment(1) });

  const assignGroup = (d: Device, groupId: string) =>
    updateDoc(doc(db, `${base}/devices/${d.id}`), { groupId: groupId || null });

  const sendCommand = (deviceIds: string[], type: CommandType, payload: Record<string, unknown> = {}) =>
    Promise.all(
      deviceIds.map((id) =>
        addDoc(collection(db, `${base}/devices/${id}/commands`), {
          type, payload, createdAt: serverTimestamp(), ackAt: null,
        }),
      ),
    );

  const visibleDevices = devices.filter((d) => !selectedGroup || d.groupId === selectedGroup);

  return (
    <>
      <h2>Sınıf × Uygulama izinleri</h2>
      <p style={{ color: "#555" }}>Kutucuğu işaretlediğiniz anda o sınıftaki çevrimiçi tabletlere uygulanır.</p>
      <div style={{ overflowX: "auto" }}>
        <table style={{ borderCollapse: "collapse" }}>
          <thead>
            <tr>
              <th style={th}>Sınıf</th>
              <th style={th}>Mod</th>
              {apps.map((a) => (
                <th key={a.id} style={th} title={a.id}>{a.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <tr key={g.id}>
                <td style={td}>
                  <button onClick={() => setSelectedGroup(selectedGroup === g.id ? null : g.id)}>
                    {selectedGroup === g.id ? "▸ " : ""}{g.name}
                  </button>
                </td>
                <td style={td}>
                  <select value={g.mode ?? "NORMAL"} onChange={(e) => setMode(g, e.target.value as Mode)}>
                    {(Object.keys(MODE_LABEL) as Mode[]).map((m) => (
                      <option key={m} value={m}>{MODE_LABEL[m]}</option>
                    ))}
                  </select>
                </td>
                {apps.map((a) => {
                  const on = (g.allowedApps ?? []).includes(a.id);
                  return (
                    <td key={a.id} style={{ ...td, textAlign: "center" }}>
                      <input type="checkbox" checked={on} onChange={() => toggleApp(g, a.id, !on)} />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>
        Tabletler {selectedGroup ? `— ${groupById.get(selectedGroup)?.name}` : "(tümü)"}{" "}
        <button onClick={() => {
          const text = prompt("Seçili tabletlere gönderilecek mesaj:");
          if (text) sendCommand(visibleDevices.map((d) => d.id), "MESSAGE", { text });
        }}>Toplu mesaj</button>
      </h2>
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={th}>Durum</th><th style={th}>Tablet</th><th style={th}>Sınıf</th>
            <th style={th}>Pil</th><th style={th}>Politika</th><th style={th}>Ekranda</th><th style={th}>İşlem</th>
          </tr>
        </thead>
        <tbody>
          {visibleDevices.map((d) => {
            const s = d.status ?? {};
            const online = s.lastSeen ? now - s.lastSeen.toMillis() < ONLINE_MS : false;
            const g = d.groupId ? groupById.get(d.groupId) : undefined;
            const applied = g && s.appliedPolicyVersion === (g.policyVersion ?? 0);
            return (
              <tr key={d.id}>
                <td style={td}>{online ? "🟢 çevrimiçi" : "⚪ çevrimdışı"}</td>
                <td style={td}>{d.label}</td>
                <td style={td}>
                  <select value={d.groupId ?? ""} onChange={(e) => assignGroup(d, e.target.value)}>
                    <option value="">— atanmamış —</option>
                    {groups.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                </td>
                <td style={td}>{s.battery != null ? `%${s.battery}` : "—"}</td>
                <td style={td}>{g ? (applied ? "✓ uygulandı" : "⏳ bekliyor") : "—"}</td>
                <td style={td}>{s.foreground ?? "ana ekran"}</td>
                <td style={td}>
                  <button onClick={() => sendCommand([d.id], "SYNC")}>Yenile</button>{" "}
                  <button onClick={() => sendCommand([d.id], "MAINTENANCE", { minutes: 10 })}>Bakım (10 dk)</button>{" "}
                  <button onClick={() => confirm(`${d.label} yeniden başlatılsın mı?`) && sendCommand([d.id], "REBOOT")}>
                    Yeniden başlat
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </>
  );
}

const th: CSSProperties = { borderBottom: "2px solid #ccc", padding: "6px 10px", textAlign: "left", whiteSpace: "nowrap" };
const td: CSSProperties = { borderBottom: "1px solid #eee", padding: "6px 10px" };
