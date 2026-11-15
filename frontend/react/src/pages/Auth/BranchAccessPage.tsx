import { useRef, useState } from "react";
import { Alert, Button, Divider, Form, Input, Select, Typography } from "antd";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api, getErrorMessage } from "../../lib/api";
import { AuthLayout } from "./AuthLayout";

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
  return <AuthLayout title={t("access")} subtitle={t("accessSubtitle")}>
    {error && <Alert type="error" message={error} showIcon />}
    {notice && <Alert type="success" message={t(notice)} showIcon />}
    <Typography.Title level={5}>{t("fromEmail")}</Typography.Title>
    <Typography.Paragraph type="secondary">{t("fromEmailHelp")}</Typography.Paragraph>
    <Form form={form} layout="vertical" onFinish={finish} disabled={busy}>
      <Form.Item name="challengeId" label={t("challenge")} rules={[{ required: true, pattern: /^[0-9a-f-]{36}$/i }]}><Input autoComplete="off" maxLength={36} /></Form.Item>
      <Form.Item name="otp" label={t("otp")} rules={[{ required: true, pattern: /^\d{8}$/ }]}><Input inputMode="numeric" autoComplete="one-time-code" maxLength={8} /></Form.Item>
      <Form.Item name="password" label={t("password")} rules={[{ required: true, pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,72}$/ }]}><Input.Password autoComplete="new-password" maxLength={72} /></Form.Item>
      <Form.Item name="confirmPassword" label={t("confirmPassword")} dependencies={["password"]}
        rules={[{ required: true }, ({ getFieldValue }) => ({ validator: (_, value: string) =>
          !value || getFieldValue("password") === value ? Promise.resolve() : Promise.reject(new Error(t("passwordMismatch"))) })]}>
        <Input.Password autoComplete="new-password" maxLength={72} />
      </Form.Item>
      <Button htmlType="submit" type="primary" loading={busy} block>{t("finishActivation")}</Button>
    </Form>
    <Divider />
    <Typography.Title level={5}>{t("requestAgain")}</Typography.Title>
    <Typography.Paragraph type="secondary">{t("requestAgainHelp")}</Typography.Paragraph>
    <Form form={requestForm} layout="vertical" onFinish={request} initialValues={{ purpose: "RESET" }} disabled={busy}>
      <Form.Item name="login" label={t("login")} rules={[{ required: true, max: 255 }]}><Input autoComplete="username" placeholder="manager.1 / staff.1 / admin@example.com" /></Form.Item>
      <Form.Item name="purpose" label={t("purpose")} rules={[{ required: true }]}><Select options={[
        { value: "ACTIVATE", label: t("activation") }, { value: "RESET", label: t("reset") },
      ]} /></Form.Item>
      <Button htmlType="submit" loading={busy}>{t("request")}</Button>
    </Form>
    <Link to="/login">{t("back")}</Link>
  </AuthLayout>;
}
