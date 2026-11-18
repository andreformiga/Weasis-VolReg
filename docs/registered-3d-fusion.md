# Registro Automático de Volumes 3D e Fusão Multimodal (feat/registered-3d-fusion)

Documentação técnica das alterações realizadas no fork [andreformiga/Weasis](https://github.com/andreformiga/Weasis/tree/feat/registered-3d-fusion) a partir de [nroduit/Weasis](https://github.com/nroduit/Weasis), do planejamento arquitetural definido nesta sessão para registro automático de volumes DICOM e da integração com o módulo OSGi `weasis-dicom-registration`.

## 1. Contexto e Motivação

Nas versões 4.7.1+ e 4.8 do [Weasis](https://weasis.org/en/tutorials/fusion/), o pipeline nativo de Image Fusion (`org.weasis.dicom.viewer2d.fusion`) realiza sobreposição puramente geométrica a partir dos metadados DICOM (`ImagePositionPatient`, `ImageOrientationPatient` e `PixelSpacing`), aplicando reamostragem trilinear em 2D e nos três planos do MPR (`FusionVolumeResampler`).

Por ser uma fusão *registration-free*, o código original impõe três restrições em `FusionCompatibility` e `FusionController`:

- Apenas modalidades funcionais (PT, NM, RTDOSE) podem ser sobrepostas a bases anatômicas (CT, MR).
- Ambas as séries precisam pertencer ao mesmo `StudyInstanceUID`.
- As séries devem compartilhar `FrameOfReferenceUID` ou apresentar sobreposição espacial prévia no espaço paciente (LPS).

Para habilitar casos de uso como fusão CT–MR, MR–MR (T1/T2/FLAIR) ou acompanhamento longitudinal entre estudos diferentes, este fork remove essas travas e adiciona suporte nativo a uma matriz homogênea 4x4 em coordenadas LPS (`fixedToMovingLps4x4`) calculada por um motor de registro 3D.

## 2. Decisão Arquitetural e Avaliação de Backends

Durante o planejamento técnico, foram avaliadas quatro alternativas para o motor de cálculo de registro:

| Backend | Integração no Weasis | Escopo Anatômico | Transformações | Suporte Desktop Nativo | Licença |
|---|---|---|---|---|---|
| SimpleITK | Nativa na JVM (bindings Java/JNI em memória) | Geral (corpo inteiro, todas as modalidades) | Rígida, Afim, B-Spline, Demons | Windows, Linux, macOS | Apache 2.0 |
| elastix / SimpleElastix | Subprocesso CLI ou wrapper JNI/C++ | Geral (pulmão, abdome, RT, neuro) | Rígida, Afim, B-Spline | Windows, Linux, macOS | Apache 2.0 |
| ANTs | Subprocesso CLI ou microsserviço | Geral e Neuro (SyN) | Rígida, Afim, Diffeomórfica | Linux, macOS, Windows (build C++) | Apache 2.0 |
| FreeSurfer (mri_coreg / mri_synthmorph) | Microsserviço REST/Docker ou CLI + lta_convert | Neuroimagem (encéfalo/crânio) | Rígida, Afim robusta, Deformável (DL) | Linux e macOS (Windows via WSL2/Docker) | FreeSurfer License + license.txt |

### Escolha Adotada

- **Cliente Desktop Multiplataforma:** SimpleITK (Java/JNI) encapsulado em um bundle OSGi dedicado (`weasis-dicom-registration`), evitando serialização em disco (NIfTI/MGZ) por meio de preenchimento *zero-copy* em `java.nio.FloatBuffer`.
- **Extensibilidade para FreeSurfer / IA:** O contrato de serviço OSGi (`VolumeRegistrationService`) foi mantido desacoplado da UI para permitir adicionar futuramente um cliente REST/Docker apontando para `mri_coreg` ou `mri_synthmorph` (com conversão obrigatória de coordenadas RAS -> LPS via `lta_convert --outitk`).

## 3. Mudanças Implementadas no Fork (weasis-dicom-viewer2d)

As alterações abaixo foram aplicadas na branch [`feat/registered-3d-fusion`](https://github.com/andreformiga/Weasis/tree/feat/registered-3d-fusion) no pacote `org.weasis.dicom.viewer2d.fusion` ([comparação completa](https://github.com/nroduit/Weasis/compare/master...andreformiga:Weasis:feat/registered-3d-fusion)):

| Commit | Arquivo | Alterações Realizadas |
|---|---|---|
| `531c3e1` | [FusionState.java](https://github.com/andreformiga/Weasis/blob/feat/registered-3d-fusion/weasis-dicom/weasis-dicom-viewer2d/src/main/java/org/weasis/dicom/viewer2d/fusion/FusionState.java) | Adiciona `double[] fixedToMovingLps4x4` ao snapshot imutável (preservando o construtor de 6 argumentos), garantindo que a matriz de registro seja herdada ao abrir o MPR Viewer a partir de uma vista 2D fusionada. |
| `4a0e1f6` | [FusionCompatibility.java](https://github.com/andreformiga/Weasis/blob/feat/registered-3d-fusion/weasis-dicom/weasis-dicom-viewer2d/src/main/java/org/weasis/dicom/viewer2d/fusion/FusionCompatibility.java) | Substitui os conjuntos restritivos por `VOLUMETRIC_MODALITIES` (CT, MR, PT, NM, RTDOSE), troca a exigência de mesmo `StudyInstanceUID` por `samePatient(...)` (via `StudyInstanceUID`, `PatientID` ou `PatientName`) e aceita pares com registro 3D calculado ou entre sessões do mesmo paciente. |
| `865c6ff` | [FusionVolumeResampler.java](https://github.com/andreformiga/Weasis/blob/feat/registered-3d-fusion/weasis-dicom/weasis-dicom-viewer2d/src/main/java/org/weasis/dicom/viewer2d/fusion/FusionVolumeResampler.java) | Adiciona sobrecargas `resampleToValue(..., fixedToMovingLps4x4)` e `resampleToGray(..., fixedToMovingLps4x4)`. Em `PlaneVectors.of(...)`, pré-multiplica apenas os 3 vetores do plano (tlhc, row e column) pela matriz 4x4, mantendo custo zero adicional por pixel no ForkJoinPool. |
| `7bb5ffe` | [FusionOp.java](https://github.com/andreformiga/Weasis/blob/feat/registered-3d-fusion/weasis-dicom/weasis-dicom-viewer2d/src/main/java/org/weasis/dicom/viewer2d/fusion/FusionOp.java) | Define o parâmetro `P_FUSION_TRANSFORM` (`fusion.transform.lps4x4`), limpa-o em `RESET_DISPLAY` / `SERIES_CHANGE`, força o caminho volumétrico 3D quando há transformação ativa (evitando aproximação 2D in-plane em rotações fora do plano) e repassa a matriz para `getStatsLayer(...)`. |
| `da7f518` | [FusionController.java](https://github.com/andreformiga/Weasis/blob/feat/registered-3d-fusion/weasis-dicom/weasis-dicom-viewer2d/src/main/java/org/weasis/dicom/viewer2d/fusion/FusionController.java) | Mantém registro em memória `registeredTransforms` por par (`baseSeriesUid`, `overlaySeriesUid`), expõe `applyRegisteredFusion(baseSeries, overlaySeries, fixedToMovingLps4x4)` para ativação imediata em 2D/MPR e amplia `compatibleSeries(...)` para varrer todos os estudos do paciente (`DicomModel.patient`). |

**Nota de visibilidade OSGi:** O `pom.xml` de `weasis-dicom-viewer2d` já configura `Export-Package: !org.weasis.dicom.viewer2d.internal,org.weasis.dicom.viewer2d.*` via `bnd-maven-plugin`, portanto `org.weasis.dicom.viewer2d.fusion` fica automaticamente acessível ao bundle do plugin.

## 4. Planejamento e Arquitetura do Plugin (weasis-dicom-registration)

O módulo complementar `weasis-dicom-registration` foi projetado como um bundle OSGi independente (Declarative Services) integrado ao `View2dContainer`:

- **API de Serviço (`api/`)**
  - `VolumeRegistrationService`: interface OSGi para registro rígido 3D (`registerRigid3D`).
  - `RegistrationParameters`: hiperparâmetros da pirâmide multirresolução (`bins = 50`, `samplingPercentage = 0.15`, `shrinkFactors = {4, 2, 1}`, `smoothingSigmasMm = {2.0, 1.0, 0.0}`).
  - `RegistrationResult`: encapsula `fixedToMovingLps4x4` (usada na reamostragem do `FusionVolumeResampler`) e `movingToFixedLps4x4` (inversa analítica exigida pelo padrão DICOM SRO).

- **Motor SimpleITK em Memória (`core/`)**
  - `SimpleItkNativeLoader`: carregamento idempotente da biblioteca JNI SimpleITKJava empacotada via `Bundle-NativeCode` (`bnd.bnd`).
  - `DicomVolumeBuilder`: ordena fatias pelo produto escalar na normal do plano, valida espaçamento Z, converte `PixelSpacing [dy, dx]` para ITK `Spacing [dx, dy, dz]`, aplica `RescaleSlope` / `RescaleIntercept` e preenche o `FloatBuffer` nativo sem I/O em disco.
  - `SimpleItkRigidRegistrationService`: executa `VersorRigid3DTransform` inicializado com `CenteredTransformInitializerFilter`, métrica `MattesMutualInformation`, otimizador `RegularStepGradientDescent` (`setOptimizerScalesFromPhysicalShift`), `Reference.reachabilityFence` no callback SWIG e liberação determinística `delete()` dos objetos C++.

- **Interoperabilidade PACS (`dicom/`)**
  - `DicomSroExporter`: gera objetos padrão DICOM Spatial Registration Storage (SOP Class UID `1.2.840.10008.5.1.4.1.1.66.1`) usando dcm4che para arquivamento em PACS (C-STORE / STOW-RS) sem alterar os pixels originais.

- **Interface do Usuário (`ui/`)**
  - `RegistrationTool` (PluginTool com MigLayout e SwingWorker assíncrono com cancelamento) e `RegistrationToolFactory` (InsertableFactory registrada para `View2dContainer`), invocando `FusionController.applyRegisteredFusion(...)` ao final do alinhamento.

## 5. Roadmap de Evolução

- **Etapa 1:** Patch no núcleo `weasis-dicom-viewer2d` para fusão multimodal cross-study com matriz `4x4` LPS em 2D e MPR.
- **Etapa 2:** Esqueleto Maven/OSGi `weasis-dicom-registration` com SimpleITK (registro rígido 6 DOF) e exportação DICOM SRO.
- **Etapa 3:** Incorporar o módulo `weasis-dicom-registration` diretamente em `weasis-dicom/pom.xml` e `weasis-distributions/pom.xml`.
- **Etapa 4:** Implementar leitor automático de objetos DICOM `REG` recebidos do PACS para popular `FusionController.registeredTransforms` ao abrir o estudo.
- **Etapa 5:** Expandir para registro afim (12 DOF) e backend opcional remoto (FreeSurfer `mri_synthmorph` / GPU).

## 6. Como Compilar e Testar Localmente

```bash
# 1. Clonar a branch com o patch aplicado
git clone -b feat/registered-3d-fusion https://github.com/andreformiga/Weasis.git
cd Weasis

# 2. Instalar o JAR Java do SimpleITK no repositório Maven local
mvn install:install-file \
  -Dfile=/caminho/para/simpleitk-2.4.0.jar \
  -DgroupId=org.itk.simple \
  -DartifactId=simpleitk \
  -Dversion=2.4.0 \
  -Dpackaging=jar

# 3. Compilar o módulo de visualização 2D/MPR modificado (requer JDK 25 LTS)
mvn clean install -DskipTests -pl weasis-dicom/weasis-dicom-viewer2d -am
```
