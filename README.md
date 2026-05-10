# Zest

Zest helps you scan packaged food labels and understand how processed they look. It focuses on the ingredient panel, gives a NOVA-style classification, highlights important ingredients, and keeps your scan history on your device.

## Why The Name Zest?

`Zest` is meant to feel fresh, clear, and food-focused. The app is built to help you cut through label noise, understand what is really inside a product, and make better everyday choices without friction.

In practice, Zest is about:

- reading labels faster
- spotting ingredient-level signals clearly
- choosing healthier options with more confidence

## Feature List

Zest is designed as a consumer-grade ingredient intelligence layer: fast enough for the grocery aisle, structured enough for product decisions, and privacy-conscious enough for everyday trust.

### Consumer Value

- Instant ingredient clarity: scan a package label and turn dense ingredient text into a plain-language processing assessment.
- NOVA-style classification: see whether a product is closer to minimally processed, processed culinary, processed, or ultra-processed.
- Red/green ingredient capsules: quickly spot which corrected ingredient names were identified as ultra-processed markers.
- Separate allergen intelligence: common US / Western allergens appear in their own section instead of being mixed into processing signals.
- Human-readable summaries: each result includes a concise, consumer-friendly one-liner that explains what the ingredient list suggests.
- Non-food rejection: if the scan does not contain a consumable food item or ingredient evidence, Zest stops early and explains why.

### Trust And Privacy

- Images stay on device: captured and uploaded label images are never sent to AI providers.
- On-device OCR first: ML Kit extracts text locally before any model call happens.
- Text-only AI analysis: providers receive extracted ingredient text, not the original image.
- Encrypted key storage: LLM and USDA API keys are stored through encrypted on-device storage.
- Local-first history: scan results and failed scans are stored locally through Room.
- No account required: users can analyze labels without sign-in or cloud sync.

### Product Intelligence

- Staged AI pipeline: one call for NOVA classification, one call for ingredient cleanup and ultra-processed marker detection, one call for allergens, and chat only on demand.
- Deterministic model settings: API calls use low-variance parameters for more consistent product behavior.
- Result-scoped chat: users can ask follow-up questions about the current scan without turning the app into a general chatbot.
- Barcode support: barcode scans can enrich analysis through USDA data when configured.
- Failed scan recovery: failed image-based scans can appear in History with a rerun path.
- Usage visibility: History shows provider-reported token and cost usage by model/provider when available, with local estimates as fallback.

### Operational Readiness

- Professional Compose UI: shared typography, brand assets, spacing, colors, splash, launcher icon, and sound settings.
- System back handling: Android back and edge-swipe gestures route within the app instead of accidentally closing it.
- Build safeguards: source-tree checks block retired demo, legacy, rule-based, and dataless source files before build work proceeds.
- Release-minded storage: Room migrations preserve history and support failed-scan records.
- Developer documentation: architecture, pipeline contracts, security, testing, and roadmap docs are maintained under `documentation/`.

## How To Set It Up

1. Install and open the app on an Android phone or tablet.
2. Review the disclaimer on first launch and tap `I agree`, then `Next`.
3. Go to `Settings`.
4. Add your AI model key if you want label analysis. Images stay on device; OCR extracts text locally.
5. Optionally add the USDA key if you want barcode lookup support.
6. Wait for the key status indicator to turn green before scanning.

## How To Use Zest

1. Open the app.
2. Choose one of these options:
   - Scan a label with the camera.
   - Upload a label photo from your gallery.
   - Scan a barcode.
3. Review the analysis result.
4. Review the ingredient capsules to see which corrected ingredients were flagged as ultra-processed markers.
5. Check the allergen block for separate allergen signals.
6. Open `History` to revisit old scans.

## What The Result Means

- `NOVA 1` usually means minimally processed.
- `NOVA 2` usually means processed culinary ingredients.
- `NOVA 3` means processed foods.
- `NOVA 4` means the label has stronger ultra-processing signals.

Ingredient capsules are color-coded from the API-returned ultra-processed marker list: red for flagged ultra-processed markers, green otherwise. Allergen signals are shown separately so they do not get mixed into the ingredient score.

## Privacy

Zest is designed to keep your data local by default.

- Scan history stays on your device.
- Saved keys are encrypted on device.
- Saved keys are not shown back in plain text.
- No sign-in is required.

When you analyze a label, OCR runs on device first. The app never sends captured or uploaded label images to the API provider. Only extracted text or corrected ingredient names are sent for NOVA classification, ingredient analysis, allergen detection, and result chat when you have configured a key.

## Important Notes

- Zest is not medical advice.
- Zest is not a nutrition label verifier.
- If the image is not a real ingredient panel or ingredient list, the app will reject it.
- The first-run disclaimer is also available from `Settings`.
- Barcode lookup depends on USDA availability and may fail if the product is missing from the database.
- API providers may rate-limit requests. If that happens, the app will tell you.

## B2 Group

Zest is built and maintained by the B2 group.

## Contributors

- Technical Advisor
  - Atul Bhagat - [LinkedIn](https://www.linkedin.com/in/bhagatatul/)
- Contributors
  - Emmy - [LinkedIn](https://www.linkedin.com/in/emmy-lin-129b26263/)
  - Emre Can Baykurt - [LinkedIn](https://www.linkedin.com/in/ebaykurt/)
  - Samet Temurcin - [LinkedIn](https://www.linkedin.com/in/samet-temurcin/)
  - Ola Ajayi - [LinkedIn](https://www.linkedin.com/in/olaajayi1234/)

If you contributed code, design, testing, or product feedback, add your name here in future releases.

## License

Zest is distributed under a modified MIT-style non-commercial license.

See the license text in [LICENSE/LICENSE.md](LICENSE/LICENSE.md).

## Support

If the app fails to analyze a label:

- Check that the ingredient panel is visible and readable.
- Try a clearer photo with better lighting.
- Confirm your API key is saved in `Settings`.
- For barcode scans, confirm USDA lookup is configured.

## Project Links

- Repository: https://github.com/benevolentbandwidth/ultraprocessed
- License: [LICENSE/LICENSE.md](LICENSE/LICENSE.md)
- Technical documentation: [documentation/README.md](documentation/README.md)
- Non-Android architecture guide: [documentation/00-android-app-guide.md](documentation/00-android-app-guide.md)
