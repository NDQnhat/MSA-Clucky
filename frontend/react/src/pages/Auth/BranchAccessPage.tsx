import { useRef, useState } from "react";
import { Alert, Button, Form, Input, Select } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api, getErrorMessage } from "../../lib/api";
import { ArrowLeft, KeyRound, MailCheck } from "lucide-react";
import styles from "./branch-access.module.css";

export function BranchAccessPage() {
  const { t } = useTranslation("accounts");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const operationLock = useRef(false);
  const [form] = Form.useForm();
  const [requestForm] = Form.useForm();
  async function request(values: { login: string; purpose: string }) {
    if (operationLock.current) return;
    operationLock.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api.post<{ challengeId: string }>("/api/auth/branch/challenge", values);
      form.setFieldsValue({ challengeId: response.data.challengeId, otp: undefined, password: undefined, confirmPassword: undefined });
      setNotice("sent");
    } catch (failure) { setError(getErrorMessage(failure)); }
    finally { operationLock.current = false; setBusy(false); }
  }
  async function finish(values: { challengeId: string; otp: string; password: string; confirmPassword: string }) {
    if (operationLock.current) return;
    operationLock.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      await api.post("/api/auth/branch/finish", {
        challengeId: values.challengeId.trim(),
        otp: values.otp,
        password: values.password,
      });
      form.resetFields(); setNotice("done");
    } catch (failure) { setError(getErrorMessage(failure)); }
    finally { operationLock.current = false; setBusy(false); }
  }
  return <main className={styles.page}>
    <header className={styles.header}>
      <Link to="/login" className={styles.brand} aria-label={t("back")}>
        <img src="/logo_primary.png" alt="Clucky" width={48} height={48} />
        <span>CLUCKY<small>MANAGEMENT SYSTEM</small></span>
      </Link>
      <Link to="/login" className={styles.backLink}><ArrowLeft size={15} aria-hidden="true" />{t("back")}</Link>
    </header>
    <section className={styles.card}>
      <div className={styles.cardHeader}>
        <span className={styles.eyebrow}><KeyRound size={14} aria-hidden="true" />{t("access")}</span>
        <h1>{t("access")}</h1>
        <p>{t("accessSubtitle")}</p>
      </div>
      {error && <Alert type="error" message={error} showIcon />}
      {notice && <Alert type="success" message={t(notice)} showIcon />}
      <div className={styles.section}>
        <div className={styles.sectionTitle}><MailCheck size={18} aria-hidden="true" /><div><h2>{t("fromEmail")}</h2><span>{t("fromEmailHelp")}</span></div></div>
        <Form form={form} layout="vertical" onFinish={finish} disabled={busy} requiredMark={false}>
          <div className={styles.grid}>
            <Form.Item name="challengeId" label={t("challenge")} rules={[{ required: true, pattern: /^[0-9a-f-]{36}$/i }]}><Input autoComplete="off" maxLength={36} /></Form.Item>
            <Form.Item name="otp" label={t("otp")} rules={[{ required: true, pattern: /^\d{8}$/ }]}><Input inputMode="numeric" autoComplete="one-time-code" maxLength={8} /></Form.Item>
            <Form.Item name="password" label={t("password")} rules={[{ required: true, pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,72}$/ }]}><Input.Password autoComplete="new-password" maxLength={72} /></Form.Item>
            <Form.Item name="confirmPassword" label={t("confirmPassword")} dependencies={["password"]}
              rules={[{ required: true }, ({ getFieldValue }) => ({ validator: (_, value: string) =>
                !value || getFieldValue("password") === value ? Promise.resolve() : Promise.reject(new Error(t("passwordMismatch"))) })]}>
              <Input.Password autoComplete="new-password" maxLength={72} />
            </Form.Item>
          </div>
          <Button htmlType="submit" type="primary" loading={busy} block>{t("finishActivation")}</Button>
        </Form>
      </div>
      <div className={styles.requestSection}>
        <div className={styles.requestHeader}><h2>{t("requestAgain")}</h2><span>{t("requestAgainHelp")}</span></div>
        <Form form={requestForm} layout="vertical" onFinish={request} initialValues={{ purpose: "RESET" }} disabled={busy} requiredMark={false}>
          <div className={styles.requestGrid}>
            <Form.Item name="login" label={t("login")} rules={[{ required: true, max: 255 }]}><Input autoComplete="username" placeholder="manager.1 / staff.1" /></Form.Item>
            <Form.Item name="purpose" label={t("purpose")} rules={[{ required: true }]}><Select options={[
              { value: "ACTIVATE", label: t("activation") }, { value: "RESET", label: t("reset") },
            ]} /></Form.Item>
          </div>
          <div className={styles.requestActions}><Button htmlType="submit" loading={busy}>{t("request")}</Button></div>
        </Form>
      </div>
    </section>
    <footer className={styles.footer}><span>Clucky</span><span>•</span><span>{t("back")}</span></footer>
  </main>;
}
