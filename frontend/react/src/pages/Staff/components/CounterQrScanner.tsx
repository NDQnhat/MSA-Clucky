import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { BrowserQRCodeReader, type IScannerControls } from '@zxing/browser';
import { useTranslation } from 'react-i18next';
import styles from './counter-order.module.css';

interface CounterQrScannerProps {
  onDetected: (value: string) => boolean | Promise<boolean>;
  onClose: () => void;
}

/** Real camera decoder. Tracks are released on close, camera switch, or modal unmount. */
export function CounterQrScanner({ onDetected, onClose }: CounterQrScannerProps) {
  const { t } = useTranslation();
  const videoRef = useRef<HTMLVideoElement>(null);
  const detectedRef = useRef(onDetected);
  const [deviceId, setDeviceId] = useState('');
  const [devices, setDevices] = useState<Array<{ deviceId: string; label: string }>>([]);
  const [error, setError] = useState('');
  const [starting, setStarting] = useState(true);
  const [decodingImage, setDecodingImage] = useState(false);
  detectedRef.current = onDetected;

  useEffect(() => {
    let alive = true;
    const video = videoRef.current;
    let controls: IScannerControls | undefined;
    let consumed = false;
    const reader = new BrowserQRCodeReader(undefined, { delayBetweenScanAttempts: 240 });
    async function begin() {
      try {
        if (!videoRef.current) return;
        if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
          throw new Error(t('business:counterLoyalty.cameraSecureContext'));
        }
        setError('');
        setStarting(true);
        controls = await reader.decodeFromVideoDevice(
          deviceId || undefined, videoRef.current,
          (result) => {
            if (alive && !consumed && result) {
              consumed = true;
              Promise.resolve(detectedRef.current(result.getText()))
                .then(accepted => {
                  // Ignore the wrong kind of QR and continue scanning the next frame.
                  if (alive && !accepted) consumed = false;
                })
                .catch(() => { if (alive) consumed = false; });
            }
          },
        );
        if (!alive) {
          controls.stop();
          return;
        }
        setStarting(false);
        try {
          const found = await BrowserQRCodeReader.listVideoInputDevices();
          if (alive) setDevices(found.map(({ deviceId: id, label }) => ({ deviceId: id, label })));
        } catch {
          // Some browsers hide camera names; scanning still works.
        }
      } catch (cause) {
        if (alive) {
          setStarting(false);
          const cameraError = cause instanceof Error ? cause : null;
          setError(
            cameraError?.message === t('business:counterLoyalty.cameraSecureContext')
              ? cameraError.message
              : cameraError?.name === 'NotAllowedError' || cameraError?.name === 'PermissionDeniedError'
                ? t('business:counterLoyalty.cameraPermission')
                : cameraError?.name === 'NotFoundError'
                  ? t('business:counterLoyalty.cameraNotFound')
                  : t('business:counterLoyalty.cameraError'),
          );
        }
      }
    }
    void begin();
    return () => {
      alive = false;
      controls?.stop();
      if (video?.srcObject instanceof MediaStream) {
        for (const track of video.srcObject.getTracks()) track.stop();
        video.srcObject = null;
      }
    };
  }, [deviceId, t]);

  const decodeImage = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || decodingImage) return;
    const imageUrl = URL.createObjectURL(file);
    setDecodingImage(true);
    setError('');
    try {
      const result = await new BrowserQRCodeReader().decodeFromImageUrl(imageUrl);
      const accepted = await detectedRef.current(result.getText());
      if (!accepted) setError(t('business:counterLoyalty.wrongQrType'));
    } catch {
      setError(t('business:counterLoyalty.imageDecodeError'));
    } finally {
      URL.revokeObjectURL(imageUrl);
      setDecodingImage(false);
    }
  };

  return (
    <div className={styles.qrCamera}>
      <div className={styles.qrVideoFrame}>
        <video ref={videoRef} autoPlay playsInline muted className={styles.qrVideo} aria-label={t('business:counterLoyalty.cameraPreview')} />
        <div className={styles.qrTarget} aria-hidden="true"><span /><span /><span /><span /></div>
      </div>
      {starting && <span role="status" className={styles.cameraStatus}>{t('business:counterLoyalty.cameraStarting')}</span>}
      {error && <span role="alert" className={styles.qrError}>{error}</span>}
      <div className={styles.qrCameraActions}>
        <label className={styles.cameraPicker}>
          {t('business:counterLoyalty.chooseCamera')}
          <select value={deviceId} disabled={devices.length < 2} onChange={event => setDeviceId(event.target.value)}>
            <option value="">{t('business:counterLoyalty.defaultCamera')}</option>
            {devices.map((device, index) => <option key={device.deviceId} value={device.deviceId}>
              {device.label || t('business:counterLoyalty.cameraNumber', { number: index + 1 })}
            </option>)}
          </select>
        </label>
        <label className={styles.qrUploadBtn}>
          {decodingImage ? t('business:counterLoyalty.imageDecoding') : t('business:counterLoyalty.uploadQrImage')}
          <input type="file" accept="image/*" disabled={decodingImage} onChange={event => void decodeImage(event)} />
        </label>
        <button className={styles.qrCloseBtn} type="button" onClick={onClose}>{t('business:counterLoyalty.closeScanner')}</button>
      </div>
      <p className={styles.qrCameraHint}>{t('business:counterLoyalty.cameraHint')}</p>
    </div>
  );
}
