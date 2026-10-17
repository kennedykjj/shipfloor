package shipfloor.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who acted. Every effect in the factory carries an actor. */
@Serializable
sealed interface Actor {
    @Serializable
    @SerialName("owner")
    data object Owner : Actor

    @Serializable
    @SerialName("agent")
    data class Agent(val role: String) : Actor

    @Serializable
    @SerialName("adapter")
    data class Adapter(val provider: String) : Actor

    @Serializable
    @SerialName("factory")
    data class Factory(val component: String) : Actor
}
