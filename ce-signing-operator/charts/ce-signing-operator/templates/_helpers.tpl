{{- define "ce-signing-operator.labels" -}}
app.kubernetes.io/name: ce-signing-operator
app.kubernetes.io/version: {{ .Chart.AppVersion }}
app.kubernetes.io/managed-by: helm
{{- end }}

{{- define "ce-signing-operator.selectorLabels" -}}
app.kubernetes.io/name: ce-signing-operator
{{- end }}
