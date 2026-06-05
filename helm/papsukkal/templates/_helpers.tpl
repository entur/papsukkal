{{- /*
  Labels, annotations and securityContext are kept identical to Entur's common chart
  (charts/common/templates/_helpers.tpl) so Papsukkal stays consistent with org conventions,
  while the CronJob itself is defined locally (templates/cronjob.yaml) to control spec.timeZone
  and jobTemplate.spec.backoffLimit, which the common chart does not expose.
*/ -}}

{{- define "name" -}}
{{ empty .Values.releaseName | ternary .Release.Name .Values.releaseName }}
{{- end -}}

{{- define "labels" }}
app: {{ empty .Values.releaseName | ternary .Release.Name .Values.releaseName }}
appId: {{ .Values.appId }}
shortname: {{ .Values.appId }}
team: {{ .Values.team }}
environment: {{ .Values.env }}
type: cronjob
app.kubernetes.io/instance: {{ empty .Values.releaseName | ternary .Release.Name .Values.releaseName }}
app.kubernetes.io/managed-by: Helm
{{- if .Values.labels }}
{{ toYaml .Values.labels }}
{{- end }}
{{- end }}

{{- define "annotations" }}
meta.helm.sh/release-name: {{ empty .Values.releaseName | ternary .Release.Name .Values.releaseName }}
meta.helm.sh/release-namespace: {{ empty .Release.Namespace| ternary .Release.Name .Release.Namespace }}
{{- end }}

{{- define "securitycontext" }}
securityContext:
  allowPrivilegeEscalation: false
  runAsNonRoot: true
  capabilities:
    drop: ["ALL"]
  seccompProfile:
    type: RuntimeDefault
{{- end }}
