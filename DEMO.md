# FilePack API - Demo: Auto Scaling, Multi-AZ e Observabilidade

## 1. Introdução

Esta demo valida a infraestrutura AWS ECS Fargate com **Auto Scaling**, **Multi-AZ** e **Observabilidade**. Assumimos que você já tem o código-fonte, vai provisionar a infraestrutura do zero e está logado no AWS CLI.

**O que vamos fazer:**
- Provisionar infraestrutura completa via CloudFormation
- Validar distribuição Multi-AZ das tasks
- Realizar teste de carga com k6 para acionar o scaling
- Observar métricas e logs no CloudWatch

**Ambiente:**
- **Região:** us-east-1
- **Cluster ECS:** filepack-cluster
- **Service ECS:** filepack-service

---

## 2. Provisionamento da Infraestrutura

O provisionamento utiliza **CloudFormation** para criar toda a infraestrutura de forma declarativa e reproduzível. São duas stacks: uma para o repositório de imagens (ECR) e outra para o cluster ECS com todos os componentes de rede, balanceamento e auto scaling.

### 2.1 Criar Repositório ECR

O **Amazon ECR** (Elastic Container Registry) é onde armazenamos as imagens Docker da aplicação. Precisa ser criado primeiro porque o ECS depende da imagem para iniciar as tasks.

📁 [`infra/1-ecr.yml`](infra/1-ecr.yml)

```bash
aws cloudformation create-stack \
  --stack-name filepack-ecr \
  --template-body file://infra/1-ecr.yml

aws cloudformation wait stack-create-complete \
  --stack-name filepack-ecr
```

Exportar variáveis:

```bash
OUTPUTS_ECR=$(aws cloudformation describe-stacks --stack-name filepack-ecr \
  --query "Stacks[0].Outputs[*].[OutputKey,OutputValue]" --output text)

export ACCOUNT_ID=$(echo "$OUTPUTS_ECR" | awk '$1 == "AccountId" {print $2}')
export REPO_URI=$(echo "$OUTPUTS_ECR" | awk '$1 == "RepositoryUri" {print $2}')

echo "Repository: $REPO_URI"
```

**Saída:**
```
Repository: 27307****022.dkr.ecr.us-east-1.amazonaws.com/filepack-api
```

O output mostra o URI completo do repositório onde enviaremos a imagem Docker.

### 2.2 Build e Push da Imagem Docker

Com o repositório criado, fazemos o build da aplicação e enviamos para o ECR. O login via `aws ecr get-login-password` gera um token temporário para autenticação.

```bash
# Login no ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com

# Build e push
./mvnw clean install
docker build -t filepack-api .
docker tag filepack-api:latest ${REPO_URI}:latest
docker push ${REPO_URI}:latest
```

### 2.3 Criar Infraestrutura ECS

Esta stack cria o cluster ECS, VPC, subnets Multi-AZ, Load Balancer, Security Groups, IAM Roles e as políticas de Auto Scaling. A flag `CAPABILITY_NAMED_IAM` é obrigatória porque estamos criando roles com nomes específicos.

📁 [`infra/2-ecs.yml`](infra/2-ecs.yml) — Principais recursos:

```yaml
# Multi-AZ: duas subnets em zonas diferentes
PublicSubnet:   # us-east-1a
PublicSubnet2:  # us-east-1b

# Auto Scaling
ScalableTarget:   # Min: 2, Max: 6 tasks
ScaleOutPolicy:   # Step Scaling +1/+2 tasks
ScaleInPolicy:    # Step Scaling -1 task
HighCpuAlarm:     # CPU > 25% → scale-out
LowCpuAlarm:      # CPU < 10% → scale-in
```

```bash
aws cloudformation create-stack \
  --stack-name filepack-ecs \
  --template-body file://infra/2-ecs.yml \
  --capabilities CAPABILITY_NAMED_IAM

aws cloudformation wait stack-create-complete \
  --stack-name filepack-ecs
```

Obter URL do Load Balancer:

```bash
export ALB_URL=$(aws cloudformation describe-stacks --stack-name filepack-ecs \
  --query "Stacks[0].Outputs[?OutputKey=='LoadBalancerUrl'].OutputValue" --output text)

echo "ALB URL: $ALB_URL"
```

**Saída:**
```
ALB URL: http://filepack-alb-*********.us-east-1.elb.amazonaws.com
```

Este é o endpoint público da aplicação. O ALB distribui requisições entre as tasks ECS e faz health checks automáticos.

---

## 3. Validação da Infraestrutura

Antes de executar testes de carga, validamos que todos os componentes estão funcionando: health check da aplicação, API respondendo, tasks distribuídas entre AZs e políticas de scaling configuradas.

### 3.1 Testar Health Check

O Spring Actuator expõe o endpoint `/actuator/health` que o ALB usa para verificar se as tasks estão saudáveis.

```bash
curl -s $ALB_URL/actuator/health
```

**Saída:**
```json
{"groups":["liveness","readiness"],"status":"UP"}
```

Status `UP` confirma que a aplicação está pronta para receber requisições. O ALB só roteia tráfego para tasks com health check OK.

### 3.2 Testar FilePack API

Teste funcional da API enviando um arquivo para compactação com senha.

📁 Arquivos de teste: [`data/`](data/)

```bash
curl -X POST $ALB_URL/api/filepack \
  -F "files=@data/infrastructure_config.xml" \
  -F "password=teste123" \
  --output validation_pack.zip
```

O arquivo `validation_pack.zip` será baixado. O conteúdo só pode ser extraído com a senha informada.

### 3.3 Verificar Tasks e Multi-AZ

**Multi-AZ** garante alta disponibilidade: se uma zona de disponibilidade falhar, a aplicação continua operando na outra. O ECS distribui tasks automaticamente entre as subnets configuradas.

```bash
aws ecs describe-services \
  --cluster filepack-cluster \
  --services filepack-service \
  --query "services[0].{Desired:desiredCount,Running:runningCount,Pending:pendingCount}" \
  --output table
```

**Saída:**
```
+---------+-----------+-----------+
| Desired |  Pending  |  Running  |
+---------+-----------+-----------+
|  2      |  0        |  2        |
+---------+-----------+-----------+
```

`Desired=2` e `Running=2` indica que o service está estável com a quantidade desejada de tasks rodando.

Verificar distribuição entre AZs:

```bash
TASKS=$(aws ecs list-tasks \
  --cluster filepack-cluster \
  --service-name filepack-service \
  --desired-status RUNNING \
  --query 'taskArns' \
  --output text)

aws ecs describe-tasks \
  --cluster filepack-cluster \
  --tasks $TASKS \
  --query 'tasks[].{AZ:availabilityZone,Status:lastStatus}' \
  --output table
```

**Saída:**
```
+-------------+-----------+
|     AZ      |  Status   |
+-------------+-----------+
|  us-east-1a |  RUNNING  |
|  us-east-1b |  RUNNING  |
+-------------+-----------+
```

Uma task em cada AZ. Se `us-east-1a` ficar indisponível, `us-east-1b` continua atendendo requisições.

### 3.4 Verificar Auto Scaling

O **Auto Scaling** ajusta automaticamente a quantidade de tasks baseado em métricas. Usamos **Step Scaling** que permite respostas diferentes para cada nível de carga.

**Comparação de estratégias de Auto Scaling:**

| Estratégia | Como Funciona | Quando Usar |
|------------|---------------|-------------|
| **Target Tracking** | Mantém métrica em valor-alvo (ex: 70% CPU) | Cargas estáveis, configuração simples |
| **Step Scaling** | Ajustes diferentes por faixa de métrica | Cargas voláteis, controle granular |
| **Scheduled** | Escala em horários específicos (cron) | Padrões temporais conhecidos |

**Nossa escolha:** Step Scaling com threshold de 25% CPU permite demonstrar o scaling rapidamente. Em produção, Target Tracking com 70% seria mais comum.

📁 Configuração em [`infra/2-ecs.yml`](infra/2-ecs.yml):

```yaml
ScaleOutPolicy:
  StepScalingPolicyConfiguration:
    StepAdjustments:
      - MetricIntervalLowerBound: 0    # CPU 25-35% → +1 task
        MetricIntervalUpperBound: 10
        ScalingAdjustment: 1
      - MetricIntervalLowerBound: 10   # CPU ≥35% → +2 tasks
        ScalingAdjustment: 2
```

```bash
aws application-autoscaling describe-scaling-policies \
  --service-namespace ecs \
  --resource-id service/filepack-cluster/filepack-service \
  --query "ScalingPolicies[].{PolicyName:PolicyName,PolicyType:PolicyType}" \
  --output table
```

**Saída:**
```
+--------------------------+---------------+
|        PolicyName        |  PolicyType   |
+--------------------------+---------------+
|  filepack-step-scale-in  |  StepScaling  |
+--------------------------+---------------+
|  filepack-step-scale-out |  StepScaling  |
+--------------------------+---------------+
```

Duas políticas configuradas: `scale-out` para aumentar capacidade sob carga e `scale-in` para reduzir quando ociosa.

### 3.5 Verificar Alarmes CloudWatch

Os **CloudWatch Alarms** monitoram métricas e disparam as políticas de scaling quando os thresholds são ultrapassados.

📁 Configuração em [`infra/2-ecs.yml`](infra/2-ecs.yml):

```yaml
HighCpuAlarm:
  Threshold: 25
  EvaluationPeriods: 1      # Reage imediatamente
  AlarmActions: [!Ref ScaleOutPolicy]

LowCpuAlarm:
  Threshold: 10
  EvaluationPeriods: 2      # Aguarda 2 min (evita flapping)
  AlarmActions: [!Ref ScaleInPolicy]
```

```bash
aws cloudwatch describe-alarms \
  --alarm-name-prefix filepack \
  --query "MetricAlarms[*].{Name:AlarmName,State:StateValue,Threshold:Threshold}" \
  --output table
```

**Saída:**
```
+------------------------------+-------+-------------+
|             Name             | State |  Threshold  |
+------------------------------+-------+-------------+
|  filepack-high-cpu-scale-out |  OK   |  25.0       |
|  filepack-low-cpu-scale-in   |  OK   |  10.0       |
+------------------------------+-------+-------------+
```

Ambos em estado `OK` indicam que a CPU está entre 10% e 25%. Quando CPU > 25%, o alarme `scale-out` entra em `ALARM` e adiciona tasks.

---

## 4. Teste de Carga com k6

O **k6** é uma ferramenta moderna de load testing que usa JavaScript para definir cenários. Vamos gerar carga suficiente para ultrapassar 25% de CPU e acionar o auto scaling.

📁 [`k6/load-test.js`](k6/load-test.js)

### 4.1 Executar Teste

**GitBash**
```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$PWD:/workspace" \
  -w /workspace \
  -e "BASE_URL=$ALB_URL" \
  grafana/k6:latest run k6/load-test.js
```

**Linux e MAC**
```bash
docker run --rm \
  -v ${PWD}:/workspace \
  -w /workspace \
  -e BASE_URL=$ALB_URL \
  grafana/k6:latest run k6/load-test.js
```

**Perfil de carga** (`k6/load-test.js`):
- 20s: ramp-up até 24 VUs
- 70s: sustenta 36 VUs
- 20s: pico de 48 VUs (força scale-out)
- 10s: ramp-down

### 4.2 Resultados Observados

```
checks_succeeded...: 100.00% 318 out of 318

✓ status is 200
✓ response has content
✓ response is zip

http_req_duration..: avg=4.15s  min=2.4s  med=3.78s  max=18.96s  p(95)=6.79s
http_req_failed....: 0.00%  0 out of 106
http_reqs..........: 106    0.56/s

data_received......: 55 MB   289 kB/s
data_sent..........: 553 MB  2.9 MB/s
```

**100% de sucesso** com 106 requisições processadas. O tempo médio de 4.15s reflete a operação de compactação + criptografia AES que é CPU-intensive. Durante o teste, o alarme de CPU dispara e novas tasks são provisionadas automaticamente.

---

## 5. Monitoramento do Auto Scaling

Após o teste de carga, verificamos o histórico de scaling para confirmar que o sistema reagiu corretamente à variação de demanda.

### 5.1 Verificar Scaling Activities

O histórico mostra cada ação de scaling com timestamp, status e causa (qual alarme disparou).

```bash
aws application-autoscaling describe-scaling-activities \
  --service-namespace ecs \
  --resource-id service/filepack-cluster/filepack-service \
  --max-results 5 \
  --query "ScalingActivities[*].{Time:StartTime,Status:StatusCode,Cause:Cause}" \
  --output table
```

**Saída típica:**
```
+-----------------------------------------------------------------------------------+------------+---------------------------+
|                                      Cause                                        |   Status   |           Time            |
+-----------------------------------------------------------------------------------+------------+---------------------------+
|  monitor alarm filepack-high-cpu-scale-out in state ALARM triggered policy...     |  Successful|  2026-04-07T11:20:15Z     |
|  monitor alarm filepack-low-cpu-scale-in in state ALARM triggered policy...       |  Successful|  2026-04-07T11:18:44Z     |
+-----------------------------------------------------------------------------------+------------+---------------------------+
```

O histórico confirma que os alarmes disparam corretamente as políticas. `scale-out` durante carga alta, `scale-in` após estabilização.

### 5.2 Estado Após Scale-out

Durante o pico de carga, verificamos quantas tasks o auto scaling provisionou.

```bash
aws ecs describe-services \
  --cluster filepack-cluster \
  --services filepack-service \
  --query "services[0].{Desired:desiredCount,Running:runningCount}" \
  --output table
```

**Saída durante carga:**
```
+---------+-----------+
| Desired |  Running  |
+---------+-----------+
|  5      |  5        |
+---------+-----------+
```

O service escalou de 2 para 5 tasks. O Step Scaling adicionou +1 task quando CPU passou de 25%, e +2 tasks quando ultrapassou 35%. Após a carga diminuir, o `scale-in` reduzirá gradualmente de volta ao mínimo de 2.

---

## 6. Observabilidade - CloudWatch

A **observabilidade** permite entender o comportamento da aplicação em produção através de métricas, logs e traces. O ECS integra nativamente com CloudWatch para centralizar essas informações.

### 6.1 Container Insights

Dashboard automático com métricas de CPU, memória, rede e disco agregadas por cluster, service e task.

**Console:** `CloudWatch > Container Insights > Resources > ECS Clusters > filepack-cluster`

### 6.2 Logs Estruturados

Logs no formato `key=value` facilitam análise e correlação. O `requestId` permite rastrear uma requisição através de todos os eventos.

📁 Modificações para logs estruturados:
- [`FilePackController.java`](src/main/java/com/josev001/filepack_api/FilePackController.java)
- [`FilePackService.java`](src/main/java/com/josev001/filepack_api/FilePackService.java)

```java
// Controller - início e fim da requisição
log.info("REQUEST_START requestId={} fileCount={} totalSizeBytes={}", requestId, ...);
log.info("REQUEST_END requestId={} zipSizeBytes={}", requestId, ...);

// Service - processo de compactação
log.info("PACK_START requestId={} fileNames={}", requestId, ...);
log.info("PACK_END requestId={} compressionTimeMs={} outputSizeBytes={}", requestId, ...);
```

**Log Group:** `/ecs/filepack-api`

📁 Configuração do log driver em [`infra/2-ecs.yml`](infra/2-ecs.yml):

```yaml
LogConfiguration:
  LogDriver: awslogs
  Options:
    awslogs-group: /ecs/filepack-api
```

Exemplo de log:
```
REQUEST_START requestId=a3f7b2c1 fileCount=4 totalSizeBytes=1048576
PACK_START requestId=a3f7b2c1 fileNames=[file1.json, file2.json, ...]
PACK_END requestId=a3f7b2c1 compressionTimeMs=342 outputSizeBytes=524288
REQUEST_END requestId=a3f7b2c1 zipSizeBytes=524288
```

Cada requisição gera 4 eventos rastreáveis pelo mesmo `requestId`, permitindo debug end-to-end.

### 6.3 CloudWatch Logs Insights Queries

Linguagem SQL-like para analisar logs em escala. Útil para debugging, análise de performance e troubleshooting.

**Rastrear requisições:**
```
fields @timestamp, @message
| filter @message like /REQUEST_START|REQUEST_END/
| parse @message "requestId=* " as requestId
| sort @timestamp desc
| limit 50
```

**Análise de performance:**
```
fields @timestamp, @message
| filter @message like /PACK_END/
| parse @message "compressionTimeMs=* " as timeMs
| stats avg(timeMs) as avgMs, max(timeMs) as maxMs, count() as total
```

---

## 7. Cleanup

Remover todos os recursos para evitar custos. A ordem é importante: primeiro o ECS (que depende do ECR), depois forçar remoção das imagens, e por fim deletar o repositório.

```bash
# 1. Deletar stack ECS
aws cloudformation delete-stack --stack-name filepack-ecs
aws cloudformation wait stack-delete-complete --stack-name filepack-ecs

# 2. Deletar repositório ECR (força remoção de imagens)
aws ecr delete-repository --repository-name filepack-api --force

# 3. Deletar stack ECR
aws cloudformation delete-stack --stack-name filepack-ecr
aws cloudformation wait stack-delete-complete --stack-name filepack-ecr

# 4. Deletar log group (opcional)
aws logs delete-log-group --log-group-name /ecs/filepack-api 2>/dev/null || true
```

**Verificar cleanup:**
```bash
aws cloudformation list-stacks \
  --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \
  --query "StackSummaries[?contains(StackName, 'filepack')].StackName" \
  --output text
```

**Saída esperada:** (vazio - nenhuma stack restante)

---

## 8. Resumo da Configuração

### Auto Scaling (Step Scaling)

| Métrica | Threshold | Ação |
|---------|-----------|------|
| CPU > 25% | +1 task (25-35%), +2 tasks (≥35%) | Scale-out rápido |
| CPU < 10% | -1 task (após 2 min) | Scale-in conservador |

### Infraestrutura

| Recurso | Configuração |
|---------|-------------|
| Tasks | Min: 2, Max: 6 |
| CPU/Memória | 256 CPU / 512 MB |
| Multi-AZ | us-east-1a, us-east-1b |
| HealthCheck | `wget -q --spider http://localhost:8080/actuator/health` |

