# 排版自检原始输出

`report-api34-avd.txt` 是 `TypographyLabTest` 在 AVD `reader34`（API 34 / sdk_gphone64_arm64 /
density 2.625 / fontScale 1.0 / 屏宽 411dp）上的原始 logcat 输出，用于让结论可被复核。

重新生成：

```bash
adb logcat -c
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ua.acclorite.book_story.typography.TypographyLabTest
adb logcat -d -s TypographyLab:* | sed 's/^.*TypographyLab: //' > docs/typography-lab/report-<device>.txt
```

注意：数值只对当前设备成立，不要跨设备比较，也不要把它当作真机性能。
