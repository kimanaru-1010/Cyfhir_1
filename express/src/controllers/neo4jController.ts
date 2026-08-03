import { Request, Response } from 'express';
import neo4j, { Driver, Session, auth } from 'neo4j-driver';
import cypher from './cypherController';

// Singleton driver - tạo 1 lần duy nhất
let driver: Driver | null = null;

function getDriver(): Driver {
  if (!driver) {
    const uri = process.env.NEO4J_URI || 'bolt://localhost:7687';
    const password = process.env.NEO4J_PASSWORD || 'password';
    driver = neo4j.driver(uri, auth.basic('neo4j', password), {
      disableLosslessIntegers: true,
      maxConnectionLifetime: 30 * 60 * 1000,
      maxConnectionPoolSize: 50,
      connectionAcquisitionTimeout: 2 * 60 * 1000,
      maxTransactionRetryTime: 10 * 60 * 1000, // 10 phút retry
    });
  }
  return driver;
}

async function verifyConnection() {
  const d = getDriver();
  try {
    await d.verifyConnectivity();
    console.log('Verified Neo4j Connection');
  } catch (error) {
    console.log(`Connectivity Verification Failed: ${error}`);
  }
}

function startTransaction(cypher: string, res) {
  try {
    const d = getDriver();
    const session: Session = d.session();

    session.writeTransaction(tx => tx.run(cypher))
      .then(result => {
        return res({ result });
      })
      .catch(error => {
        console.log(error);
        return res({ error });
      })
      .finally(() => {
        session.close();
      });
  } catch (error) {
    console.log(error);
    return res({ error });
  }
}
// }1. Nhận vào một chuỗi Cypher
// 2. Mở kết nối Neo4j
// 3. Tạo transaction ghi
// 4. Chạy câu Cypher bằng tx.run(cypher)
// 5. Nếu thành công, đóng session và driver
// 6. Trả result
// 7. Nếu lỗi, trả error

function loadBundleNeo4j(_bundle, res: Response) {
  startTransaction(cypher.loadBundle(_bundle), (result) => {
    if (result) {
      return res.status(200).send(result);
    } else {
      return res.status(500).send({
        error: 'Error'
      });
    }
  });
}

function loadBundleNeo4jPromise(_bundle: any): Promise<any> {
  return new Promise((resolve, reject) => {
    startTransaction(cypher.loadBundle(_bundle), (result: any) => {
      if (result && result.result) {
        resolve(result.result);
      } else {
        reject(new Error(result?.error?.message || 'Error loading bundle'));
      }
    });
  });
}

function deleteAllNodes(req: Request, res: Response) {
  startTransaction(cypher.deleteAll(), (result) => {
    if (result && result.result !== undefined) {
      return res.status(200).send('All nodes deleted');
    } else {
      return res.status(500).send(result?.error || 'Error');
    }
  });
}

function getBundle(_id: string, res: Response) {
  startTransaction(cypher.buildBundleAroundID(_id), (result) => {
    if (result.result) {
      const bundle = result.result.records[0]._fields[0];
      if (Object.keys(bundle).length === 0) {
        return res.status(400).send({
          message: `Entry with ID ${_id} not found`
        });
      }
      return res.status(200).send(bundle);
    } else {
      return res.status(500).send(result.error);
    }
  });
}

function getBundleWithFilter(_id: string, _filter: string, res: Response) {
  startTransaction(cypher.buildBundleAroundIDWithFilter(_id, _filter), (result) => {
    if (result.result) {
      const bundle = result.result.records[0]._fields[0];
      if (Object.keys(bundle).length === 0) {
        return res.status(400).send({
          message: `Entry with ID ${_id} not found`
        });
      }
      return res.status(200).send(bundle);
    } else {
      return res.status(500).send(result.error);
    }
  });
}

function loadResourceNeo4j(_resource, res: Response) {
  startTransaction(cypher.loadResource(_resource), (result) => {
    if (result) {
      return res.status(200).send(result);
    } else {
      return res.status(500).send({
        error: 'Error'
      });
    }
  });
}

function getResource(_id: string, res: Response) {
  startTransaction(cypher.getResource(_id), (result) => {
    if (result.result) {
      const resource = result.result.records[0]._fields[0];
      if (Object.keys(resource).length === 0) {
        return res.status(400).send({
          message: `Resource with ID ${_id} not found`
        });
      }
      return res.status(200).send(resource);
    } else {
      return res.status(500).send(result.error);
    }
  });
}

// Strip large binary data from entries before loading
function sanitizeEntry(entry: any): any {
  if (!entry?.resource) return entry;
  const r = entry.resource;
  // Strip binary data from all resources
  if (r.data !== undefined) {
    r.data = undefined;
  }
  // Strip text.div (generated HTML) from all resources
  if (r.text?.div !== undefined) {
    r.text.div = undefined;
  }
  // DocumentReference: strip attachment data
  if (r.resourceType === 'DocumentReference' && r.content) {
    r.content = r.content.map((c: any) => {
      if (c.attachment && c.attachment.data !== undefined) {
        c.attachment.data = undefined;
      }
      return c;
    });
  }
  return entry;
}

async function loadFromFhirServer(_params: any, res: Response) {
  const fhirBaseUrl = _params.fhirBaseUrl || 'http://172.16.12.230:8084/fhir';
  const resourceType = _params.resourceType;
  const searchParams = _params.searchParams || '';

  if (!resourceType) {
    return res.status(400).send({ error: 'resourceType is required' });
  }

  // Use smaller page size for large resources
  const largeResources = ['Binary', 'DocumentReference', 'Observation', 'Claim', 'ClaimResponse'];
  const pageSize = largeResources.indexOf(resourceType) >= 0 ? '100' : '500';

  try {
    let url = `${fhirBaseUrl}/${resourceType}${searchParams ? '?' + searchParams : `?_count=${pageSize}`}`;
    let pageNum = 0;

    while (url) {
      pageNum++;
      console.log(`Fetching page ${pageNum} for ${resourceType}: ${url.substring(0, 120)}...`);

      const response = await fetch(url);
      if (!response.ok) {
        const errText = await response.text();
        return res.status(response.status).send({
          error: `Failed to fetch from FHIR server: ${response.status} ${response.statusText}`,
          detail: errText
        });
      }

      const bundle = await response.json();
      if (bundle.resourceType !== 'Bundle') {
        return res.status(400).send({ error: 'Response is not a FHIR Bundle' });
      }

      if (bundle.entry) {
        const sanitized = bundle.entry.map((e: any) => sanitizeEntry(e));
        const pageBundle = {
          resourceType: 'Bundle',
          type: 'collection',
          total: sanitized.length,
          entry: sanitized
        };
        await loadBundleNeo4jPromise(pageBundle);
      }

      // Rewrite next link URL to use external address instead of internal Docker name
      const nextLink = bundle.link?.find((l: any) => l.relation === 'next');
      url = nextLink
        ? nextLink.url
            .replace('http://hapi-fhir:8080/fhir', fhirBaseUrl)
            .replace('http://172.16.12.230:8014/fhir', fhirBaseUrl)
        : null;
    }

    return res.status(200).send({ message: `Done loading ${resourceType}` });
  } catch (error: any) {
    console.error('loadFromFhirServer error:', error);
    return res.status(500).send({
      error: 'Failed to load from FHIR server',
      detail: error.message
    });
  }
}

export = {
  loadBundle: (bundle, res: Response) => {
    return loadBundleNeo4j(bundle, res);
  },
  deleteAll: (req: Request, res: Response) => {
    return deleteAllNodes(req, res);
  },
  buildBundle: (_id: string, _filter: string, res: Response) => {
    if (_filter && Object.keys(_filter).length > 0) {
      return getBundleWithFilter(_id, _filter, res);
    } else {
      return getBundle(_id, res);
    }
  },
  loadResource: (resource, res: Response) => {
    return loadResourceNeo4j(resource, res);
  },
  getFhirResource: (_id: string, res: Response) => {
      return getResource(_id, res);
  },
  verifyConnection: () => {
    return verifyConnection();
  },
  loadFromFhirServer: (params: any, res: Response) => {
    return loadFromFhirServer(params, res);
  },
  loadAllResources: (params: any, res: Response) => {
    return loadAllResources(params, res);
  },
  closeDriver: () => {
    if (driver) {
      return driver.close();
    }
  }
};

async function loadAllResources(_params: any, res: Response) {
  const fhirBaseUrl = _params.fhirBaseUrl || 'http://172.16.12.230:8012/fhir';

  // List of resource types from Neo4j
  const resourceTypes = [


    'Binary',
    // 'ValueSet',
    'StructureDefinition',
    'CodeSystem',
    // 'NamingSystem',

    'Library',
    'PlanDefinition',
    'ActivityDefinition',


    'Patient',

    'Organization',
    'Location',

    'Practitioner',
    'PractitionerRole',

    'Medication',

    'Encounter',
    'ServiceRequest',
    'Procedure',

    'Observation',
    'Media',
    'DocumentReference',
    'ImagingStudy',
    'DiagnosticReport',

    'Condition',

    'Composition',

    'Coverage',
    'Claim',
    'ExplanationOfBenefit',

    'MedicationRequest',
    'MedicationStatement',


     ];

  const largeResourceTypes = new Set([
    'Binary',
    'DocumentReference',
    'ValueSet',
    'CodeSystem',
    'StructureDefinition',
    'Library',
    'NamingSystem',
  ]);

  // Strip large binary data before loading to avoid oversized bundles
  function sanitizeEntry(entry: any): any {
    if (!entry?.resource) return entry;
    const r = entry.resource;
    // Strip binary data from all resources
    if (r.data !== undefined) {
      r.data = undefined;
    }
    // Strip text.div (generated HTML) from all resources
    if (r.text?.div !== undefined) {
      r.text.div = undefined;
    }
    // DocumentReference: strip attachment data
    if (r.resourceType === 'DocumentReference' && r.content) {
      r.content = r.content.map((c: any) => {
        if (c.attachment && c.attachment.data !== undefined) {
          c.attachment.data = undefined;
        }
        return c;
      });
    }
    return entry;
  }

  const results: any[] = [];

  for (const resourceType of resourceTypes) {
    try {
      let totalLoaded = 0;
      const pageSize = largeResourceTypes.has(resourceType) ? '100' : '500';
      let url = `${fhirBaseUrl}/${resourceType}?_count=${pageSize}`;
      let pageNum = 0;

      while (url) {
        pageNum++;
        console.log(`Loading ${resourceType} page ${pageNum}...`);

        const response = await fetch(url);
        if (!response.ok) {
          console.error(`Failed to fetch ${resourceType} page ${pageNum}: ${response.status}`);
          break;
        }

        const bundle = await response.json();
        if (bundle.entry?.length) {
          // Sanitize entries (strip large binary data)
          const sanitized = bundle.entry.map((e: any) => sanitizeEntry(e));
          const pageBundle = {
            resourceType: 'Bundle',
            type: 'collection',
            total: sanitized.length,
            entry: sanitized
          };
          await loadBundleNeo4jPromise(pageBundle);
          totalLoaded += sanitized.length;
        }

        // Rewrite next link URL to use external address instead of internal Docker name
        const nextLink = bundle.link?.find((l: any) => l.relation === 'next');
        url = nextLink
          ? nextLink.url
              .replace('http://hapi-fhir:8080/fhir', fhirBaseUrl)
              .replace('http://172.16.12.230:8014/fhir', fhirBaseUrl)
          : null;
      }

      if (totalLoaded > 0) {
        results.push({ resourceType, loaded: totalLoaded });
        console.log(`Done: ${totalLoaded} ${resourceType} loaded`);
      } else {
        results.push({ resourceType, loaded: 0, skipped: true });
      }
    } catch (error: any) {
      console.error(`Error loading ${resourceType}:`, error.message);
      results.push({ resourceType, error: error.message });
    }
  }

  const totalAll = results.reduce((sum, r) => sum + (r.loaded || 0), 0);
  return res.status(200).send({
    message: `Loaded ${totalAll} resources across ${results.filter((r: any) => r.loaded > 0).length} resource types`,
    results
  });
}
