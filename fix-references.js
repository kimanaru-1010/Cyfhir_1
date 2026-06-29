const fs = require('fs');

const files = [
  'cyfhir/src/test/resources/DSTU3Bundle.json',
  'cyfhir/src/test/resources/R4Bundle.json',
  'cyfhir/src/test/resources/R5Bundle.json',
  'cyfhir/src/test/resources/PatientOnlyBundle.json',
];

// Known reference fields -> resource type mapping
// Các nested object trong FHIR có trường "reference", cần biết resource type để convert
const REF_TYPE_MAP = {
  // Direct references
  subject: 'Patient',
  patient: 'Patient',
  encounter: 'Encounter',
  source: 'Practitioner',
  requester: 'Practitioner',
  practitioner: 'Practitioner',
  doctor: 'Practitioner',
  provider: 'Practitioner',
  organization: 'Organization',
  careTeam: 'CareTeam',
  condition: 'Condition',
  diagnosis: 'Condition',
  medication: 'Medication',
  medicationRequest: 'MedicationRequest',
  prescription: 'MedicationRequest',
  vaccine: 'Immunization',
  immunization: 'Immunization',
  claim: 'Claim',
  explanationOfBenefit: 'ExplanationOfBenefit',
  coverage: 'Coverage',
  relatedPerson: 'RelatedPerson',
  location: 'Location',
  device: 'Device',
  specimen: 'Specimen',
  related: 'RelatedPerson',
  serviceProvider: 'Organization',
  referringProvider: 'Practitioner',

  // Participant / performer nested refs
  participant: 'Practitioner',
  performer: 'Practitioner',
  individual: 'Practitioner',
  // Organization nested
  containing: 'Organization',
  endpoint: 'Endpoint',
  // Generic fallback for unknown parent types in arrays
};

files.forEach((file) => {
  if (!fs.existsSync(file)) return;

  // Bước 1: Tạo bảng tra UUID -> FHIR ID từ fullUrl của entry
  // fullUrl hiện tại có thể là FHIR ID ("Patient/abc-123") hoặc "urn:uuid:abc-123"
  const fhirIdMap = {};
  let data = JSON.parse(fs.readFileSync(file, 'utf8'));
  data.entry.forEach((entry) => {
    if (entry.fullUrl) {
      let uuid, resourceType;
      if (entry.fullUrl.startsWith('urn:uuid:')) {
        uuid = entry.fullUrl.replace('urn:uuid:', '');
        resourceType = entry.resource?.resourceType;
      } else {
        // Đã là FHIR ID: "ResourceType/uuid"
        const parts = entry.fullUrl.split('/');
        resourceType = parts[0];
        uuid = parts.slice(1).join('/');
      }
      if (uuid && resourceType) {
        fhirIdMap[uuid] = `${resourceType}/${uuid}`;
      }
    }
  });

  // Step 2: Convert references in nested objects using field name mapping
  function convertRefs(obj, parentType) {
    if (!obj || typeof obj !== 'object') return;
    if (Array.isArray(obj)) {
      obj.forEach((item) => convertRefs(item, parentType));
    } else {
      for (const [key, value] of Object.entries(obj)) {
        if (key === 'resourceType') {
          // Update parentType for nested recursion
          convertRefs(value, key);
        } else if (key === 'reference' && typeof value === 'string' && value.startsWith('urn:uuid:')) {
          const uuid = value.replace('urn:uuid:', '');
          // Determine resource type from parent field name
          let resourceType = parentType && REF_TYPE_MAP[parentType] ? REF_TYPE_MAP[parentType] : null;
          if (resourceType && fhirIdMap[uuid]) {
            obj[key] = fhirIdMap[uuid];
          }
        } else {
          convertRefs(value, key);
        }
      }
    }
  }

  // Convert all nested references
  data.entry.forEach((entry) => {
    convertRefs(entry.resource, entry.resource?.resourceType);
  });

  // Step 3: Convert fullUrl and top-level reference from urn:uuid: to FHIR ID
  data.entry.forEach((entry) => {
    if (entry.fullUrl && entry.fullUrl.startsWith('urn:uuid:')) {
      const uuid = entry.fullUrl.replace('urn:uuid:', '');
      const resourceType = entry.resource?.resourceType;
      if (resourceType) {
        entry.fullUrl = `${resourceType}/${uuid}`;
      }
    }
    if (entry.reference && entry.reference.startsWith('urn:uuid:')) {
      const uuid = entry.reference.replace('urn:uuid:', '');
      if (fhirIdMap[uuid]) {
        entry.reference = fhirIdMap[uuid];
      }
    }
  });

  // Step 4: Write back as text (preserve formatting)
  let content = JSON.stringify(data, null, 2);
  fs.writeFileSync(file, content, 'utf8');
  console.log(`Updated: ${file}`);
});
