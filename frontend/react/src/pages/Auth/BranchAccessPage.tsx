import { useRef, useState } from "react";
import { Alert, Button, Form, Input, Radio } from "antd";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api, getErrorMessage } from "../../lib/api";
import { useAuthState } from "../../context/AuthContext";
import { roleHome } from "../../lib/role-home";
import { ArrowLeft, KeyRound, MailCheck, RefreshCw } from "lucide-react";
import styles from "./branch-access.module.css";

export function BranchAccessPage() {
  const { t } = useTranslation("accounts");
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { user } = useAuthState();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const operationLock = useRef(false);
  const [form] = Form.useForm();
  const [requestForm] = Form.useForm();
  const purpose = Form.useWatch("purpose", requestForm) ?? "ACTIVATE";
  async function request(values: { login: string; purpose: string }) {
    if (operationLock.current) return;
    operationLock.current = true;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api.post<{ challengeId: string }>("/api/auth/branch/challenge", values);
      form.setFieldsValue({ challengeId: response.data.challengeId, otp: undefined, password: undefined, confirmPassword: undefined });
      setNotice("requestQueued");
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
      form.resetFields();
      navigate("/login", { replace: true, state: { branchPasswordUpdated: true } });
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
      {user && <Alert type="info" showIcon message={t("alreadySignedIn", { login: user.email || user.fullName })}
        action={<Link to={roleHome(user.role)}>{t("backToDashboard")}</Link>} />}
      <div className={styles.requestSection}>
        <div className={styles.sectionTitle}><RefreshCw size={18} aria-hidden="true" /><div><h2>{t("requestAgain")}</h2><span>{t("requestAgainHelp")}</span></div></div>
        <Form form={requestForm} layout="vertical" onFinish={request}
          initialValues={{ login: params.get("login") ?? "", purpose: params.get("purpose") === "RESET" ? "RESET" : "ACTIVATE" }}
          disabled={busy} requiredMark={false}>
          <Form.Item name="purpose" label={t("purpose")} rules={[{ required: true }]}>
            <Radio.Group className={styles.purposeOptions} optionType="button" buttonStyle="solid"
              options={[{ value: "ACTIVATE", label: t("firstActivation") }, { value: "RESET", label: t("reset") }]} />
          </Form.Item>
          <p className={styles.purposeHelp}>{t(purpose === "RESET" ? "resetHelp" : "activationHelp")}</p>
          <Form.Item name="login" label={t("login")} rules={[{ required: true, max: 255 }]}>
            <Input autoComplete="username" placeholder="manager.1 / staff.1" />
          </Form.Item>
          <div className={styles.requestActions}><Button icon={<RefreshCw size={16} />} htmlType="submit" loading={busy}>{t("sendOrResend")}</Button></div>
        </Form>
      </div>
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
    </section>
    <footer className={styles.footer}><span>Clucky</span><span>•</span><span>{t("back")}</span></footer>
  </main>;
}
