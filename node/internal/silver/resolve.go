package silver

import (
	"fmt"
	"sort"
	"strings"
)

type entityProfile struct {
	ID           string
	Names, Types map[string]bool
}

type mention struct{ Name, Type string }

func resolve(dataset Dataset, observations []Observation, profileContext ProfileContext, now int64) ([]Entity, []Claim, error) {
	profiles := profiles(dataset)
	entities := map[string]Entity{}
	claims := map[string]Claim{}
	producer := Producer{ProcessorID: ResolutionProcessorID, ProcessorVersion: ResolutionVersion}
	sort.Slice(observations, func(i, j int) bool { return observations[i].ID < observations[j].ID })
	for _, observation := range observations {
		payload, ok := observation.Payload.(map[string]any)
		if !ok {
			continue
		}
		subject, ok := parseMention(payload["subject"])
		if !ok {
			continue
		}
		predicate, ok := payload["predicate"].(string)
		if !ok || predicate == "" {
			continue
		}
		authoredBySelf, _ := payload["authoredBySelf"].(bool)
		subjectID, created, profile, err := resolveMention(subject, profiles, profileContext, authoredBySelf)
		if err != nil {
			return nil, nil, err
		}
		if subjectID == "" {
			continue
		}
		if created != nil {
			entities[created.ID] = *created
			profiles = append(profiles, profile)
		}
		if !contextualSelfMention(subject, authoredBySelf) {
			addMentionToProfile(profiles, subjectID, subject)
			if err = addMetadataClaims(claims, subjectID, subject, observation.ID, producer, now); err != nil {
				return nil, nil, err
			}
		}

		var objectID *string
		var value any
		if observation.Kind == "relationship-candidate" {
			object, valid := parseMention(payload["object"])
			if !valid {
				continue
			}
			resolved, objectEntity, objectProfile, resolveErr := resolveMention(object, profiles, profileContext, authoredBySelf)
			if resolveErr != nil {
				return nil, nil, resolveErr
			}
			if resolved == "" {
				continue
			}
			if resolved == subjectID {
				continue
			}
			if objectEntity != nil {
				entities[objectEntity.ID] = *objectEntity
				profiles = append(profiles, objectProfile)
			}
			if !contextualSelfMention(object, authoredBySelf) {
				addMentionToProfile(profiles, resolved, object)
				if err = addMetadataClaims(claims, resolved, object, observation.ID, producer, now); err != nil {
					return nil, nil, err
				}
			}
			objectID = &resolved
		} else if observation.Kind == "attribute-candidate" {
			value = scalar(payload["value"])
			if value == nil {
				continue
			}
		} else {
			continue
		}
		claim, createErr := NewClaim(subjectID, predicate, objectID, value, []string{observation.ID}, nil, producer, now)
		if createErr != nil {
			return nil, nil, createErr
		}
		claims[claim.ID] = claim
	}
	entityList := make([]Entity, 0, len(entities))
	for _, item := range entities {
		entityList = append(entityList, item)
	}
	claimList := make([]Claim, 0, len(claims))
	for _, item := range claims {
		claimList = append(claimList, item)
	}
	sort.Slice(entityList, func(i, j int) bool { return entityList[i].ID < entityList[j].ID })
	sort.Slice(claimList, func(i, j int) bool { return claimList[i].ID < claimList[j].ID })
	return entityList, claimList, nil
}

func profiles(dataset Dataset) []entityProfile {
	var result []entityProfile
	for _, entity := range dataset.Entities {
		profile := entityProfile{ID: entity.ID, Names: map[string]bool{}, Types: map[string]bool{}}
		for _, claim := range dataset.Claims {
			if claim.SubjectEntityID != entity.ID || claim.State != "active" {
				continue
			}
			text, ok := claim.Value.(string)
			if !ok {
				continue
			}
			if claim.Predicate == "name" {
				profile.Names[matchText(text)] = true
			}
			if claim.Predicate == "entity-type" {
				profile.Types[matchText(text)] = true
			}
		}
		result = append(result, profile)
	}
	return result
}

func resolveMention(value mention, profiles []entityProfile, context ProfileContext, authoredBySelf bool) (string, *Entity, entityProfile, error) {
	name, typeName := matchText(value.Name), matchText(value.Type)
	if typeName == "person" && firstPersonName(name) {
		if authoredBySelf {
			return context.SelfEntityID, nil, entityProfile{}, nil
		}
		return "", nil, entityProfile{}, nil
	}
	var sameName, exact []entityProfile
	for _, profile := range profiles {
		if profile.Names[name] {
			sameName = append(sameName, profile)
			if profile.Types[typeName] {
				exact = append(exact, profile)
			}
		}
	}
	if len(exact) == 1 {
		return exact[0].ID, nil, entityProfile{}, nil
	}
	if len(sameName) > 0 {
		return "", nil, entityProfile{}, nil
	}
	if typeName == "person" {
		var aliases []entityProfile
		for _, profile := range profiles {
			if !profile.Types["person"] {
				continue
			}
			for existingName := range profile.Names {
				if personNameAlias(name, existingName) {
					aliases = append(aliases, profile)
					break
				}
			}
		}
		if len(aliases) == 1 {
			return aliases[0].ID, nil, entityProfile{}, nil
		}
	}
	entity, err := newEntity()
	if err != nil {
		return "", nil, entityProfile{}, err
	}
	profile := entityProfile{ID: entity.ID, Names: map[string]bool{name: true}, Types: map[string]bool{typeName: true}}
	return entity.ID, &entity, profile, nil
}

func addMentionToProfile(profiles []entityProfile, entityID string, value mention) {
	for index := range profiles {
		if profiles[index].ID == entityID {
			profiles[index].Names[matchText(value.Name)] = true
			profiles[index].Types[matchText(value.Type)] = true
			return
		}
	}
}

func firstPersonName(value string) bool {
	switch value {
	case "i", "me", "my", "myself":
		return true
	default:
		return false
	}
}

func contextualSelfMention(value mention, authoredBySelf bool) bool {
	return authoredBySelf && matchText(value.Type) == "person" && firstPersonName(matchText(value.Name))
}

func personNameAlias(left, right string) bool {
	leftParts, rightParts := strings.Fields(left), strings.Fields(right)
	if len(leftParts) == 1 && len(rightParts) > 1 {
		return containsNamePart(rightParts, leftParts[0])
	}
	if len(rightParts) == 1 && len(leftParts) > 1 {
		return containsNamePart(leftParts, rightParts[0])
	}
	return false
}

func containsNamePart(parts []string, part string) bool {
	if len([]rune(part)) < 2 {
		return false
	}
	for _, candidate := range parts {
		if candidate == part {
			return true
		}
	}
	return false
}

func addMetadataClaims(destination map[string]Claim, entityID string, value mention, observationID string, producer Producer, now int64) error {
	for predicate, scalarValue := range map[string]string{"name": value.Name, "entity-type": value.Type} {
		claim, err := NewClaim(entityID, predicate, nil, scalarValue, []string{observationID}, nil, producer, now)
		if err != nil {
			return err
		}
		destination[claim.ID] = claim
	}
	return nil
}

func parseMention(value any) (mention, bool) {
	object, ok := value.(map[string]any)
	if !ok {
		return mention{}, false
	}
	name, nameOK := object["name"].(string)
	typeName, typeOK := object["type"].(string)
	return mention{Name: name, Type: typeName}, nameOK && typeOK && name != "" && typeName != ""
}

func matchText(value string) string { return strings.ToLower(strings.Join(strings.Fields(value), " ")) }

func debugProfile(profile entityProfile) string {
	return fmt.Sprintf("%s:%d:%d", profile.ID, len(profile.Names), len(profile.Types))
}
